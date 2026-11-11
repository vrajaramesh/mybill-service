package com.example.mybill.boutique.service;

import com.example.mybill.boutique.entity.WaIntakeBatch;
import com.example.mybill.boutique.entity.WaIntakeMessage;
import com.example.mybill.boutique.repository.WaIntakeBatchRepository;
import com.example.mybill.boutique.repository.WaIntakeMessageRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.hibernate.query.NativeQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * WhatsApp → Dropship, phase 3: a post whose supplier is chosen (EXTRACTING) becomes a DRAFT dropship collection.
 * <ol>
 *   <li>Claude reads the supplier's text (title, category, model, border, pallu, blouse, supplier price, "+ shipping",
 *       hashtags, a 2-4 letter code prefix) and each photo, several per request (colour, blouse / secondary
 *       description, watermark or phone number on it, quality).</li>
 *   <li>dHash per photo: near-duplicates within the post and of products added in the last 30 days are skipped.</li>
 *   <li>Codes {prefix}{ddMM}[A|B…], variants -{nn} (MGP1111-07); selling price = supplier price × margin rounded up to
 *       a price ending in 49 / 99 (dropship.pricing.*).</li>
 *   <li>One Products row (pcs, cost = supplier price, stock 0, not online yet) + collection item per photo; the
 *       collection is DROPSHIP, DRAFT, source WHATSAPP_FORWARD, with the supplier's default courier charges.</li>
 * </ol>
 * The AI calls run outside any DB transaction; everything is written in one short transaction at the end, so a
 * failure leaves nothing half-made (the batch is then FAILED with the reason and the owner is told).
 */
@Service
public class DropshipExtractionService {

    private static final Logger log = LoggerFactory.getLogger(DropshipExtractionService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final int IMAGES_PER_CALL = 8;

    @Autowired private ClaudeVisionClient claude;
    @Autowired private WaIntakeBatchRepository batches;
    @Autowired private WaIntakeMessageRepository messages;
    @Autowired private DropshipService dropship;
    @Autowired private CollectionService collections;
    @Autowired private ApplicationEventPublisher events;
    @Autowired private TransactionTemplate tx;
    @Autowired private ObjectMapper json;
    @PersistenceContext private EntityManager em;

    @Value("${dropship.pricing.margin:1.30}") private BigDecimal margin;
    @Value("${dropship.pricing.endings:49,99}") private String endings;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    static final String TEXT_PROMPT = """
        You read a WhatsApp post from an Indian saree / dress-material supplier and return ONLY a JSON object, no other text:
        {"title": "short catalogue title, e.g. Mangalagiri Pattu Saree",
         "category": "saree | dress material | kurti | lehenga | dupatta | other",
         "model": "model / design name or null", "border": "border description or null", "pallu": "pallu description or null",
         "blouse": "blouse description or null",
         "supplier_price": number (the price per piece, e.g. 1650 for "PRICE- 1650+ SHIPPING"; null if none),
         "shipping_extra": true if shipping is charged on top ("+ shipping", "+ship"), else false,
         "suggested_hashtags": ["5-10 relevant Instagram hashtags without spaces, e.g. #mangalagiripattu"],
         "code_prefix": "2-4 capital letters from the product type, e.g. MGP for Mangalagiri Pattu"}
        Never copy phone numbers, supplier names or WhatsApp group names into any field.""";

    static final String IMAGE_PROMPT = """
        Each image is one colour of the same product from a supplier post. Return ONLY a JSON array, one object per image in
        the order given, no other text:
        [{"index": 1, "primary_colour": "main body colour in simple words, e.g. maroon",
          "secondary_desc": "short description of contrast parts, e.g. red floral Kalamkari blouse, gold zari border",
          "has_watermark_or_phone": true if a watermark, logo, shop name or phone number is visible,
          "quality_ok": false if blurry, very dark, cut off or a collage}]""";

    /** Runs the extraction for one batch (idempotent: only an EXTRACTING batch without a collection is processed). */
    public Map<String, Object> extract(int batchId) {
        Snapshot snap = tx.execute(s -> snapshot(batchId));
        if (snap == null) return Map.of("status", "skipped", "reason", "not_extracting");
        try {
            Draft draft = build(snap);
            Map<String, Object> result = tx.execute(s -> save(snap, draft));
            log.info("DROPSHIP_DRAFT batch={} result={}", batchId, result);
            return result;
        } catch (Exception e) {
            log.error("DROPSHIP_EXTRACTION_FAILED batch={}: {}", batchId, e.toString(), e);
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            tx.executeWithoutResult(s -> fail(batchId, reason));
            return Map.of("status", "failed", "error", reason);
        }
    }

    // ── 1. read the batch ────────────────────────────────────────────────────

    private record Photo(int no, String url) {}

    private record Snapshot(WaIntakeBatch batch, String text, List<Photo> photos, DropshipService.Supplier supplier) {}

    private Snapshot snapshot(int batchId) {
        WaIntakeBatch b = batches.findById(batchId).orElse(null);
        if (b == null || !WaIntakeBatch.EXTRACTING.equals(b.getStatus()) || b.getCollectionId() != null) return null;
        List<WaIntakeMessage> msgs = messages.findByBatchIdOrderByReceivedAtAscMessageIdAsc(batchId);
        String text = msgs.stream().map(WaIntakeMessage::getText).filter(t -> t != null && !t.isBlank())
            .collect(Collectors.joining("\n"));
        List<Photo> photos = new ArrayList<>();
        for (WaIntakeMessage m : msgs) {
            if (WaIntakeMessage.IMAGE.equals(m.getMsgType()) && m.getGcsPath() != null) photos.add(new Photo(photos.size() + 1, m.getGcsPath()));
        }
        DropshipService.Supplier supplier = dropship.supplier(b.getSupplierId())
            .orElseThrow(() -> new IllegalStateException("Supplier " + b.getSupplierId() + " not found"));
        return new Snapshot(b, text, photos, supplier);
    }

    // ── 2. AI + hashes (no transaction) ──────────────────────────────────────

    private record Variant(Photo photo, String phash, String colour, String secondary, boolean watermark, boolean qualityOk) {}

    private record Draft(JsonNode info, List<Variant> variants, List<String> skipped, BigDecimal supplierPrice,
                         BigDecimal sellingPrice) {}

    private Draft build(Snapshot snap) {
        if (snap.photos().isEmpty()) throw new IllegalStateException("The post has no photos");
        JsonNode info = claude.json(TEXT_PROMPT, List.of(ClaudeVisionClient.text(
            snap.text().isBlank() ? "(the supplier sent no text)" : snap.text())), 1200);
        if (!info.isObject()) throw new ClaudeVisionClient.BadJson("Expected a JSON object for the post text");

        // duplicates: within the post, then against products of the last 30 days
        List<String> skipped = new ArrayList<>();
        List<String> recent = recentHashes();
        List<Photo> kept = new ArrayList<>();
        Map<Integer, String> hashes = new HashMap<>();
        for (Photo p : snap.photos()) {
            String h = ImageHash.dHash(download(p.url()));
            String sameInPost = hashes.entrySet().stream().filter(e -> ImageHash.distance(e.getValue(), h) <= ImageHash.NEAR_DUPLICATE_BITS)
                .map(e -> "photo " + e.getKey()).findFirst().orElse(null);
            boolean sameAsRecent = h != null && recent.stream().anyMatch(r -> ImageHash.distance(r, h) <= ImageHash.NEAR_DUPLICATE_BITS);
            if (h != null && sameInPost != null) { skipped.add("photo " + p.no() + " (same as " + sameInPost + ")"); continue; }
            if (sameAsRecent) { skipped.add("photo " + p.no() + " (already added in the last 30 days)"); continue; }
            if (h != null) hashes.put(p.no(), h);
            kept.add(p);
        }
        if (kept.isEmpty()) throw new IllegalStateException("All photos are duplicates of products added before");

        // colours, several photos per request
        Map<Integer, JsonNode> perPhoto = new HashMap<>();
        for (int i = 0; i < kept.size(); i += IMAGES_PER_CALL) {
            List<Photo> chunk = kept.subList(i, Math.min(i + IMAGES_PER_CALL, kept.size()));
            List<Map<String, Object>> content = new ArrayList<>();
            for (int j = 0; j < chunk.size(); j++) {
                content.add(ClaudeVisionClient.text("Image " + (j + 1) + ":"));
                content.add(ClaudeVisionClient.imageUrl(chunk.get(j).url()));
            }
            content.add(ClaudeVisionClient.text("Product: " + info.path("title").asText("") + ". Describe the " + chunk.size() + " image(s)."));
            JsonNode arr = claude.json(IMAGE_PROMPT, content, 300 + 120 * chunk.size());
            if (!arr.isArray()) throw new ClaudeVisionClient.BadJson("Expected a JSON array for the photos");
            for (int j = 0; j < chunk.size(); j++) {
                JsonNode match = null;
                for (JsonNode n : arr) if (n.path("index").asInt(-1) == j + 1) match = n;
                if (match == null && j < arr.size()) match = arr.get(j);
                perPhoto.put(chunk.get(j).no(), match);
            }
        }
        List<Variant> variants = new ArrayList<>();
        for (Photo p : kept) {
            JsonNode n = perPhoto.get(p.no());
            variants.add(new Variant(p, hashes.get(p.no()),
                n == null ? null : blank(n.path("primary_colour").asText(null)),
                n == null ? null : blank(n.path("secondary_desc").asText(null)),
                n != null && n.path("has_watermark_or_phone").asBoolean(false),
                n == null || n.path("quality_ok").asBoolean(true)));
        }

        BigDecimal supplierPrice = info.hasNonNull("supplier_price") && info.get("supplier_price").isNumber()
            ? info.get("supplier_price").decimalValue() : null;
        if (supplierPrice == null || supplierPrice.signum() <= 0) {
            throw new IllegalStateException("No price found in the supplier's text - forward the post with its price "
                + "(e.g. PRICE- 1650+ SHIPPING)");
        }
        return new Draft(info, variants, skipped, supplierPrice, sellingPrice(supplierPrice));
    }

    /** supplier price × margin, rounded UP to the next price ending in one of the endings (default 49 / 99). */
    BigDecimal sellingPrice(BigDecimal supplierPrice) {
        long target = supplierPrice.multiply(margin).setScale(0, RoundingMode.CEILING).longValue();
        List<Integer> ends = Arrays.stream(endings.split(",")).map(String::trim).filter(s -> !s.isEmpty())
            .map(Integer::valueOf).sorted().toList();
        if (ends.isEmpty()) return BigDecimal.valueOf(target);
        long hundreds = target / 100 * 100;
        for (int e : ends) if (hundreds + e >= target) return BigDecimal.valueOf(hundreds + e);
        return BigDecimal.valueOf(hundreds + 100 + ends.get(0));
    }

    private List<String> recentHashes() {
        return tx.execute(s -> em.createNativeQuery("""
                SELECT i.image_phash FROM boutique_collection_items i JOIN products p ON p.product_id = i.product_id
                 WHERE i.image_phash IS NOT NULL AND p.created_at > NOW() - INTERVAL '30 days'""").getResultList()
            .stream().map(String::valueOf).toList());
    }

    private byte[] download(String url) {
        try {
            HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
            return r.statusCode() == 200 ? r.body() : new byte[0];
        } catch (Exception e) {
            log.warn("Photo download failed {}: {}", url, e.toString());
            return new byte[0];
        }
    }

    // ── 3. write (one transaction) ───────────────────────────────────────────

    private Map<String, Object> save(Snapshot snap, Draft d) {
        WaIntakeBatch b = em.find(WaIntakeBatch.class, snap.batch().getBatchId(), LockModeType.PESSIMISTIC_WRITE);
        if (!WaIntakeBatch.EXTRACTING.equals(b.getStatus()) || b.getCollectionId() != null) {
            return Map.of("status", "skipped", "reason", "already_done");   // a parallel run won
        }
        JsonNode info = d.info();
        String title = clip(blankOr(info.path("title").asText(null), "Dropship collection"), 120);
        String code = freeCode(prefix(info.path("code_prefix").asText(null)) + LocalDate.now(IST).format(DateTimeFormatter.ofPattern("ddMM")));
        String category = clip(blankOr(info.path("category").asText(null), "saree"), 50);
        String hashtags = String.join(" ", jsonStrings(info.path("suggested_hashtags")));
        boolean shippingExtra = info.path("shipping_extra").asBoolean(true);

        StringBuilder desc = new StringBuilder();
        for (String f : List.of("model", "border", "pallu", "blouse")) {
            String v = blank(info.path(f).asText(null));
            if (v != null) desc.append(Character.toUpperCase(f.charAt(0))).append(f.substring(1)).append(": ").append(v).append('\n');
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("name", title);
        body.put("type", CollectionService.DROPSHIP);
        body.put("description", desc.toString().trim());
        body.put("ai_instructions", "Each code is one colour of the same " + category + ". Price ₹" + d.sellingPrice().toPlainString()
            + " per piece" + (shippingExtra ? "; delivery charge extra by location (added at the summary)" : "") + ".");
        body.put("status", "DRAFT");
        body.put("source", "WHATSAPP_FORWARD");
        body.put("shipping_extra", shippingExtra);
        body.put("supplier_id", snap.supplier().id());
        if (snap.supplier().defaultCourierSlabs() != null && !snap.supplier().defaultCourierSlabs().isEmpty()) {
            body.put("courier_slabs", snap.supplier().defaultCourierSlabs());
        }
        if (snap.supplier().defaultCourierExtraPerPiece() != null) body.put("courier_extra_per_piece", snap.supplier().defaultCourierExtraPerPiece());
        if (!d.variants().isEmpty()) body.put("cover_image_url", d.variants().get(0).photo().url());
        int collectionId = ((Number) collections.save(null, body).get("collection_id")).intValue();

        List<Map<String, Object>> items = new ArrayList<>();
        int n = 0;
        for (Variant v : d.variants()) {
            n++;
            String colour = v.colour() == null ? "Colour " + n : capitalise(v.colour());
            int productId = insertProduct(title + " – " + colour, desc + (v.secondary() == null ? "" : "\n" + v.secondary()),
                category, d.supplierPrice(), d.sellingPrice(), hashtags, v.photo().url());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("item_code", code + "-" + String.format("%02d", n));
            item.put("product_id", productId);
            item.put("colour", clip(colour, 80));
            item.put("image_url", v.photo().url());
            item.put("variant_no", n);
            item.put("secondary_desc", v.secondary());
            item.put("original_image_path", v.photo().url());
            item.put("image_phash", v.phash());
            item.put("status", CollectionService.AVAILABLE);
            items.add(item);
        }
        collections.replaceItems(collectionId, items);

        List<String> flagged = d.variants().stream().filter(v -> v.watermark() || !v.qualityOk())
            .map(v -> code + "-" + String.format("%02d", d.variants().indexOf(v) + 1) + (v.watermark() ? " watermark/phone" : "")
                + (!v.qualityOk() ? " low quality" : "")).toList();
        Map<String, Object> extracted = new LinkedHashMap<>();
        extracted.put("model", claude.model());
        extracted.put("text", info);
        extracted.put("supplier_price", d.supplierPrice());
        extracted.put("selling_price", d.sellingPrice());
        extracted.put("hashtags", jsonStrings(info.path("suggested_hashtags")));
        extracted.put("skipped_duplicates", d.skipped());
        extracted.put("flagged", flagged);
        b.setExtracted(toJson(extracted));
        b.setCollectionId(collectionId);
        b.setStatus(WaIntakeBatch.AWAITING_APPROVAL);
        b.setError(null);

        String msg = "📝 Draft " + code + " ready: " + title + "\n" + d.variants().size() + " colour(s) · ₹" + d.sellingPrice().toPlainString()
            + " each (supplier ₹" + d.supplierPrice().toPlainString() + (shippingExtra ? " + shipping" : "") + ")"
            + (d.skipped().isEmpty() ? "" : "\nSkipped " + d.skipped().size() + " duplicate photo(s)")
            + (flagged.isEmpty() ? "" : "\n⚠️ Check: " + String.join(", ", flagged))
            + "\nIt's a DRAFT in mybill → Collections (" + code + ") – not sold yet.";
        tell(b, msg);
        return Map.of("status", "draft", "collection_id", collectionId, "code", code, "variants", d.variants().size(),
            "skipped", d.skipped().size(), "selling_price", d.sellingPrice());
    }

    private int insertProduct(String name, String description, String category, BigDecimal cost, BigDecimal price,
                              String tags, String imageUrl) {
        Object id = em.createNativeQuery("""
                INSERT INTO products (product_name, description, category, unit, cost_price, selling_price, stock_quantity,
                                      min_stock_level, is_active, is_online, tags, created_at, updated_at)
                VALUES (?1, ?2, ?3, 'pcs', ?4, ?5, 0, 0, TRUE, FALSE, ?6, NOW(), NOW()) RETURNING product_id""")
            .unwrap(NativeQuery.class)
            .setParameter(1, clip(name, 200), String.class).setParameter(2, description.trim(), String.class)
            .setParameter(3, category, String.class).setParameter(4, cost, BigDecimal.class)
            .setParameter(5, price, BigDecimal.class).setParameter(6, tags.isBlank() ? null : tags, String.class)
            .getSingleResult();
        int productId = ((Number) id).intValue();
        em.createNativeQuery("""
                INSERT INTO product_images (product_id, image_url, media_type, sort_order, created_at)
                VALUES (?1, ?2, 'image', 0, NOW())""")
            .setParameter(1, productId).setParameter(2, imageUrl).executeUpdate();
        return productId;
    }

    /** {prefix}{ddMM}, then A, B, … while a collection or item already uses it. */
    private String freeCode(String base) {
        for (int i = -1; i < 26; i++) {
            String code = i < 0 ? base : base + (char) ('A' + i);
            Number used = (Number) em.createNativeQuery("""
                    SELECT (SELECT COUNT(*) FROM boutique_collections WHERE UPPER(code) = ?1)
                         + (SELECT COUNT(*) FROM boutique_collection_items WHERE UPPER(item_code) LIKE ?2)""")
                .setParameter(1, code).setParameter(2, code + "-%").getSingleResult();
            if (used.intValue() == 0) return code;
        }
        throw new IllegalStateException("No free code for " + base);
    }

    static String prefix(String raw) {
        String p = raw == null ? "" : raw.toUpperCase(Locale.ROOT).replaceAll("[^A-Z]", "");
        if (p.length() > 4) p = p.substring(0, 4);
        return p.length() < 2 ? "DS" : p;
    }

    private void fail(int batchId, String reason) {
        WaIntakeBatch b = em.find(WaIntakeBatch.class, batchId, LockModeType.PESSIMISTIC_WRITE);
        if (b == null || !WaIntakeBatch.EXTRACTING.equals(b.getStatus())) return;
        b.setStatus(WaIntakeBatch.FAILED);
        b.setError(clip(reason, 1000));
        tell(b, "⚠️ Post #" + batchId + ": I couldn't build the collection – " + clip(reason, 300));
    }

    private void tell(WaIntakeBatch b, String text) {
        events.publishEvent(new DropshipIntakeService.OwnerMessage(b.getFromNumber(), "dropship:" + b.getBatchId() + ":" + UUID.randomUUID(),
            "text", text, null, null, null, null));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static List<String> jsonStrings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr != null && arr.isArray()) for (JsonNode n : arr) if (n.isTextual() && !n.asText().isBlank()) out.add(n.asText().trim());
        return out;
    }

    private String toJson(Object o) {
        try { return json.writeValueAsString(o); } catch (Exception e) { return null; }
    }

    private static String blank(String s) {
        return s == null || s.isBlank() || s.equalsIgnoreCase("null") ? null : s.trim();
    }

    private static String blankOr(String s, String fallback) {
        String b = blank(s);
        return b == null ? fallback : b;
    }

    private static String clip(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static String capitalise(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}

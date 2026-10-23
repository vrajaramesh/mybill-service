package com.example.mybill.boutique.service;

import com.example.mybill.boutique.exception.AgentApiException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.query.NativeQuery;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

/**
 * Collections (db/boutique/V13): one Instagram post with several items of the same kind and price - fabrics sold by
 * the metre (FABRIC) or readymade garments with their own sizes (READYMADE). Each item is a Products row with the code
 * printed on its photo (e.g. MCS-1A). Price, stock, sizes and photos always come from Products.
 * Used by the mybill-ui Collections page and, read-only, by the WhatsApp assistant (/api/agent/collections).
 * JSON keys are snake_case for both.
 */
@Service
public class CollectionService {

    public static final String FABRIC = "FABRIC", READYMADE = "READYMADE";
    static final Set<String> METRE_UNITS = Set.of("m", "meter", "meters", "metre", "metres", "mts", "mtr", "mtrs");
    static final Set<String> PIECE_UNITS = Set.of("pcs", "pc", "piece", "pieces", "nos", "no");
    static final int MAX_ITEMS = 60;
    private static final String CODE = "^[A-Z0-9][A-Z0-9-]{1,29}$";

    @PersistenceContext private EntityManager em;

    public static boolean isMetres(String unit) {
        return unit != null && METRE_UNITS.contains(unit.trim().toLowerCase(Locale.ROOT));
    }

    public static boolean isPieces(String unit) {
        return unit != null && PIECE_UNITS.contains(unit.trim().toLowerCase(Locale.ROOT));
    }

    // ── read ──────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object r : em.createNativeQuery("""
                SELECT c.collection_id, c.code, c.name, c.collection_type, c.is_active, c.cover_image_url,
                       (SELECT COUNT(*) FROM boutique_collection_items i WHERE i.collection_id = c.collection_id)
                  FROM boutique_collections c ORDER BY c.created_at DESC, c.collection_id DESC""").getResultList()) {
            Object[] a = (Object[]) r;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("collection_id", a[0]);
            m.put("code", a[1]);
            m.put("name", a[2]);
            m.put("type", a[3]);
            m.put("active", a[4]);
            m.put("cover_image_url", a[5]);
            m.put("item_count", ((Number) a[6]).intValue());
            out.add(m);
        }
        return out;
    }

    /** A collection with its items (product name, unit, price, stock, sizes, photo, instructions). */
    @Transactional(readOnly = true)
    public Map<String, Object> get(String codeOrId) {
        Object[] c = header(codeOrId);
        int id = ((Number) c[0]).intValue();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("collection_id", id);
        out.put("code", c[1]);
        out.put("name", c[2]);
        out.put("type", c[3]);
        out.put("description", c[4]);
        out.put("cover_image_url", c[5]);
        out.put("ai_instructions", c[6]);
        out.put("active", c[7]);
        List<Map<String, Object>> items = items(id);
        out.put("items", items);
        out.put("warnings", warnings((String) c[3], items));
        return out;
    }

    /** One item by its code (e.g. MCS-1B), with its collection's code, name, type and instructions. */
    @Transactional(readOnly = true)
    public Map<String, Object> item(String itemCode) {
        List<?> rows = em.createNativeQuery("SELECT collection_id FROM boutique_collection_items WHERE UPPER(item_code) = UPPER(?1)")
            .setParameter(1, itemCode.trim()).getResultList();
        if (rows.isEmpty()) throw AgentApiException.notFound("ITEM_NOT_FOUND", "No collection item " + itemCode);
        Map<String, Object> collection = get(String.valueOf(((Number) rows.get(0)).intValue()));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) collection.remove("items");
        Map<String, Object> item = items.stream()
            .filter(i -> itemCode.trim().equalsIgnoreCase((String) i.get("item_code"))).findFirst().orElseThrow();
        collection.remove("warnings");
        item.put("collection", collection);
        return item;
    }

    private Object[] header(String codeOrId) {
        boolean numeric = codeOrId.matches("\\d{1,9}");
        List<?> rows = em.createNativeQuery("""
                SELECT collection_id, code, name, collection_type, description, cover_image_url, ai_instructions, is_active
                  FROM boutique_collections WHERE """ + (numeric ? "collection_id = CAST(?1 AS INTEGER)" : "UPPER(code) = UPPER(?1)"))
            .setParameter(1, codeOrId.trim()).getResultList();
        if (rows.isEmpty()) throw AgentApiException.notFound("COLLECTION_NOT_FOUND", "No collection " + codeOrId);
        return (Object[]) rows.get(0);
    }

    private List<Map<String, Object>> items(int collectionId) {
        List<?> rows = em.createNativeQuery("""
                SELECT i.item_id, i.item_code, i.product_id, i.colour, i.image_url, i.sort_order,
                       p.product_name, p.unit, p.selling_price, p.stock_quantity, p.is_active, p.is_online, p.ai_instructions,
                       p.description,
                       (SELECT pi.image_url FROM product_images pi WHERE pi.product_id = i.product_id
                          AND COALESCE(pi.media_type, 'image') = 'image' ORDER BY COALESCE(pi.sort_order, 0), pi.image_id LIMIT 1)
                  FROM boutique_collection_items i LEFT JOIN products p ON p.product_id = i.product_id
                 WHERE i.collection_id = ?1 ORDER BY i.sort_order, i.item_id""")
            .setParameter(1, collectionId).getResultList();
        Map<Integer, List<Map<String, Object>>> sizes = sizesFor(rows.stream().map(r -> ((Number) ((Object[]) r)[2]).intValue()).toList());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object r : rows) {
            Object[] a = (Object[]) r;
            int productId = ((Number) a[2]).intValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("item_id", a[0]);
            m.put("item_code", a[1]);
            m.put("product_id", productId);
            m.put("colour", a[3]);
            m.put("image_url", a[4] != null ? a[4] : a[14]);
            m.put("product_name", a[6]);
            m.put("unit", a[7]);
            m.put("price", a[8] == null ? null : PricingService.money(new BigDecimal(a[8].toString())));
            m.put("stock", a[9] == null ? BigDecimal.ZERO : new BigDecimal(a[9].toString()));
            m.put("available", a[6] != null && Boolean.TRUE.equals(a[10]) && !Boolean.FALSE.equals(a[11]));
            m.put("ai_instructions", a[12]);
            m.put("description", a[13]);
            m.put("sizes", sizes.getOrDefault(productId, List.of()));
            out.add(m);
        }
        return out;
    }

    private Map<Integer, List<Map<String, Object>>> sizesFor(List<Integer> productIds) {
        Map<Integer, List<Map<String, Object>>> out = new HashMap<>();
        if (productIds.isEmpty()) return out;
        for (Object r : em.createNativeQuery("""
                SELECT product_id, size_label, stock FROM product_size_stock
                 WHERE product_id IN (?1) ORDER BY product_id, sort_order, size_label""")
                .setParameter(1, productIds).getResultList()) {
            Object[] a = (Object[]) r;
            out.computeIfAbsent(((Number) a[0]).intValue(), k -> new ArrayList<>())
                .add(Map.of("size", a[1], "stock", new BigDecimal(a[2].toString())));
        }
        return out;
    }

    /** Things staff should fix: wrong unit for the type, missing sizes, out of stock, prices that differ. */
    private static List<String> warnings(String type, List<Map<String, Object>> items) {
        List<String> out = new ArrayList<>();
        Set<BigDecimal> prices = new TreeSet<>();
        for (Map<String, Object> i : items) {
            String code = (String) i.get("item_code");
            if (i.get("product_name") == null) { out.add(code + ": product no longer exists"); continue; }
            String unit = (String) i.get("unit");
            if (FABRIC.equals(type) && !isMetres(unit)) out.add(code + ": unit is '" + unit + "', fabric items must be sold in metres");
            if (READYMADE.equals(type) && !isPieces(unit)) out.add(code + ": unit is '" + unit + "', readymade items must be in pieces");
            if (READYMADE.equals(type) && ((List<?>) i.get("sizes")).isEmpty()) out.add(code + ": no sizes with stock entered in Products");
            if (((BigDecimal) i.get("stock")).signum() <= 0) out.add(code + ": out of stock");
            if (!Boolean.TRUE.equals(i.get("available"))) out.add(code + ": product is inactive or not online");
            if (i.get("price") != null) prices.add((BigDecimal) i.get("price"));
        }
        if (prices.size() > 1) out.add("Items have different prices: " + prices + " – a collection usually has one price");
        return out;
    }

    // ── write (mybill-ui) ─────────────────────────────────────────────────────

    /** Create (collectionId null) or update. Body: code, name, type, description, cover_image_url, ai_instructions, active. */
    @Transactional
    public Map<String, Object> save(Integer collectionId, Map<String, Object> body) {
        String code = str(body.get("code")).toUpperCase(Locale.ROOT);
        String name = str(body.get("name"));
        String type = str(body.get("type")).toUpperCase(Locale.ROOT);
        if (!code.matches(CODE)) throw bad("Code must be 2-30 letters, digits or '-', e.g. MCS-1");
        if (name.isEmpty()) throw bad("Name is required");
        if (!FABRIC.equals(type) && !READYMADE.equals(type)) throw bad("Type must be FABRIC or READYMADE");
        List<?> clash = em.createNativeQuery("SELECT collection_id FROM boutique_collections WHERE UPPER(code) = ?1")
            .setParameter(1, code).getResultList();
        if (!clash.isEmpty() && (collectionId == null || ((Number) clash.get(0)).intValue() != collectionId)) {
            throw new AgentApiException(HttpStatus.CONFLICT, "COLLECTION_CODE_TAKEN", "Code " + code + " is already used", null);
        }
        boolean active = !(body.get("active") instanceof Boolean b) || b;
        NativeQuery<?> q;
        if (collectionId == null) {
            q = em.createNativeQuery("""
                    INSERT INTO boutique_collections (code, name, collection_type, description, cover_image_url, ai_instructions, is_active)
                    VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7) RETURNING collection_id""").unwrap(NativeQuery.class);
        } else {
            header(String.valueOf(collectionId));
            q = em.createNativeQuery("""
                    UPDATE boutique_collections SET code = ?1, name = ?2, collection_type = ?3, description = ?4,
                           cover_image_url = ?5, ai_instructions = ?6, is_active = ?7, updated_at = NOW()
                     WHERE collection_id = ?8 RETURNING collection_id""").unwrap(NativeQuery.class);
            q.setParameter(8, collectionId, Integer.class);
        }
        q.setParameter(1, code, String.class).setParameter(2, name, String.class).setParameter(3, type, String.class)
            .setParameter(4, nullable(body.get("description")), String.class)
            .setParameter(5, nullable(body.get("cover_image_url")), String.class)
            .setParameter(6, nullable(body.get("ai_instructions")), String.class)
            .setParameter(7, active, Boolean.class);
        int id = ((Number) q.getSingleResult()).intValue();
        return get(String.valueOf(id));
    }

    /** Replace the items: [{item_code, product_id, colour?, image_url?}] in display order. */
    @Transactional
    public Map<String, Object> replaceItems(int collectionId, List<Map<String, Object>> items) {
        header(String.valueOf(collectionId));
        if (items.size() > MAX_ITEMS) throw bad("At most " + MAX_ITEMS + " items per collection");
        Set<String> codes = new HashSet<>();
        for (Map<String, Object> item : items) {
            String code = str(item.get("item_code")).toUpperCase(Locale.ROOT);
            if (!code.matches(CODE)) throw bad("Item code '" + code + "' must be 2-30 letters, digits or '-', e.g. MCS-1A");
            if (!codes.add(code)) throw bad("Item code " + code + " is used twice");
            if (!(item.get("product_id") instanceof Number)) throw bad("Choose a product for " + code);
        }
        if (!codes.isEmpty()) {
            List<?> elsewhere = em.createNativeQuery("""
                    SELECT i.item_code, c.code FROM boutique_collection_items i JOIN boutique_collections c ON c.collection_id = i.collection_id
                     WHERE UPPER(i.item_code) IN (?1) AND i.collection_id <> ?2""")
                .setParameter(1, codes).setParameter(2, collectionId).getResultList();
            if (!elsewhere.isEmpty()) {
                Object[] a = (Object[]) elsewhere.get(0);
                throw new AgentApiException(HttpStatus.CONFLICT, "ITEM_CODE_TAKEN", "Item code " + a[0] + " is already in collection " + a[1], null);
            }
        }
        em.createNativeQuery("DELETE FROM boutique_collection_items WHERE collection_id = ?1").setParameter(1, collectionId).executeUpdate();
        int order = 0;
        for (Map<String, Object> item : items) {
            em.createNativeQuery("""
                    INSERT INTO boutique_collection_items (collection_id, item_code, product_id, colour, image_url, sort_order)
                    VALUES (?1, ?2, ?3, ?4, ?5, ?6)""").unwrap(NativeQuery.class)
                .setParameter(1, collectionId, Integer.class)
                .setParameter(2, str(item.get("item_code")).toUpperCase(Locale.ROOT), String.class)
                .setParameter(3, ((Number) item.get("product_id")).intValue(), Integer.class)
                .setParameter(4, nullable(item.get("colour")), String.class)
                .setParameter(5, nullable(item.get("image_url")), String.class)
                .setParameter(6, order++, Integer.class)
                .executeUpdate();
        }
        return get(String.valueOf(collectionId));
    }

    @Transactional
    public void delete(int collectionId) {
        Object links = em.createNativeQuery("""
                SELECT COUNT(*) FROM marketing_links l JOIN boutique_collections c ON c.code = l.collection_code
                 WHERE c.collection_id = ?1""").setParameter(1, collectionId).getSingleResult();
        if (((Number) links).intValue() > 0) {
            throw new AgentApiException(HttpStatus.CONFLICT, "COLLECTION_IN_USE",
                "This collection is used by an Ad & post link - remove the link first, or mark the collection inactive", null);
        }
        em.createNativeQuery("DELETE FROM boutique_collections WHERE collection_id = ?1").setParameter(1, collectionId).executeUpdate();
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString().trim();
    }

    private static String nullable(Object o) {
        String s = str(o);
        return s.isEmpty() ? null : s;
    }

    private static AgentApiException bad(String message) {
        return new AgentApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message, null);
    }
}

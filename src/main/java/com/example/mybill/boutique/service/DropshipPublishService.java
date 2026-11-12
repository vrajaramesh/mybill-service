package com.example.mybill.boutique.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.query.NativeQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * WhatsApp → Dropship, phase 5: an approved dropship collection is posted to the dropship Instagram account
 * (dropship.instagram.username, default @srisa_sarees - never the shop's main account) as carousel posts of up to
 * 10 code-stamped photos, each post saved as an Ad & post link (IG_MEDIA → collection) so "Chat on WhatsApp" from it
 * opens that collection; its variants are pushed to the Meta catalog with a WhatsApp link per code.
 *
 * Graph API with META_ACCESS_TOKEN (system user): /me/accounts finds the Page whose Instagram account has that
 * username and uses that Page's token. Runs outside any DB transaction (it takes 20-90 s); posting twice is
 * prevented by the saved links (a collection that already has IG posts isn't posted again unless repost=true).
 */
@Service
public class DropshipPublishService {

    private static final Logger log = LoggerFactory.getLogger(DropshipPublishService.class);
    static final int CAROUSEL_MAX = 10;

    @PersistenceContext private EntityManager em;
    @Autowired private CollectionService collections;
    @Autowired private TransactionTemplate tx;
    @Autowired private ObjectMapper json;

    @Value("${meta.access.token:}") private String accessToken;
    @Value("${instagram.graph.url:https://graph.facebook.com/v21.0}") private String graphUrl;
    @Value("${dropship.instagram.username:srisa_sarees}") private String igUsername;
    @Value("${meta.catalog.id:}") private String catalogId;
    @Value("${dropship.whatsapp.number:919491647691}") private String shopWhatsapp;
    @Value("${dropship.catalog.enabled:true}") private boolean catalogEnabled;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    /** Where it was posted. error = why Instagram or the catalog failed (the collection stays approved). */
    public record Result(String account, List<String> permalinks, int catalogItems, String error) {}

    public Result publish(int collectionId, boolean repost) {
        Map<String, Object> c = tx.execute(s -> collections.get(String.valueOf(collectionId), true));
        String code = (String) c.get("code");
        if (!"ACTIVE".equals(c.get("status"))) {
            return new Result(igUsername, List.of(), 0, code + " is " + c.get("status") + " - approve it before posting");
        }
        @SuppressWarnings("unchecked")
        List<String> existing = (List<String>) c.getOrDefault("instagram_urls", List.of());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = ((List<Map<String, Object>>) c.get("items")).stream()
            .filter(i -> !CollectionService.SOLD_OUT.equals(i.get("status")))
            .filter(i -> i.get("stamped_image_url") != null || i.get("image_url") != null).toList();
        if (items.isEmpty()) return new Result(igUsername, List.of(), 0, "No available variants with photos");

        List<String> errors = new ArrayList<>();
        List<String> links = new ArrayList<>(existing);
        if (!existing.isEmpty() && !repost) {
            log.info("DROPSHIP_PUBLISH collection={} already on Instagram ({}), not reposting", code, existing.size());
        } else {
            try {
                Account acc = account();
                List<List<Map<String, Object>>> posts = chunks(items);
                for (int p = 0; p < posts.size(); p++) {
                    String mediaId = post(acc, posts.get(p), caption(c, posts.get(p), p + 1, posts.size()));
                    String permalink = field(acc, mediaId, "permalink");
                    saveLink(code, mediaId, permalink, "Instagram @" + acc.username() + " " + code
                        + (posts.size() > 1 ? " (" + (p + 1) + "/" + posts.size() + ")" : ""));
                    links.add(permalink);
                    log.info("DROPSHIP_IG_POSTED collection={} media={} {}", code, mediaId, permalink);
                }
            } catch (Exception e) {
                log.error("DROPSHIP_IG_FAILED collection={}: {}", code, e.toString());
                errors.add("Instagram: " + e.getMessage());
            }
        }
        int catalog = 0;
        if (catalogEnabled) {
            try {
                catalog = pushCatalog(c, items);
            } catch (Exception e) {
                log.error("DROPSHIP_CATALOG_FAILED collection={}: {}", code, e.toString());
                errors.add("Meta catalog: " + e.getMessage());
            }
        }
        return new Result(igUsername, links, catalog, errors.isEmpty() ? null : String.join("; ", errors));
    }

    // ── Instagram ────────────────────────────────────────────────────────────

    private record Account(String igUserId, String username, String pageToken) {}

    /** The Page whose Instagram account is @{dropship.instagram.username}, with that Page's token. */
    Account account() {
        if (accessToken == null || accessToken.isBlank()) throw new IllegalStateException("META_ACCESS_TOKEN is not set");
        JsonNode pages = get(graphUrl + "/me/accounts?fields=name,access_token,instagram_business_account%7Bid,username%7D&limit=100"
            + "&access_token=" + enc(accessToken));
        List<String> seen = new ArrayList<>();
        for (JsonNode p : pages.path("data")) {
            JsonNode ig = p.path("instagram_business_account");
            String user = ig.path("username").asText("");
            if (!user.isEmpty()) seen.add("@" + user);
            if (user.equalsIgnoreCase(igUsername.replaceFirst("^@", ""))) {
                return new Account(ig.path("id").asText(), user, p.path("access_token").asText());
            }
        }
        throw new IllegalStateException("@" + igUsername + " is not reachable with mybill's Meta token (it can post to "
            + (seen.isEmpty() ? "no Instagram account" : String.join(", ", seen)) + "). In Business settings, connect "
            + "@" + igUsername + " to a Facebook Page and assign that Page and the Instagram account to the system user.");
    }

    /** One post: a single image, or a carousel of 2-10. Returns the published media id. */
    private String post(Account acc, List<Map<String, Object>> items, String caption) throws InterruptedException {
        String container;
        if (items.size() == 1) {
            container = create(acc, Map.of("image_url", photo(items.get(0)), "caption", caption));
        } else {
            List<String> children = new ArrayList<>();
            for (Map<String, Object> it : items) children.add(create(acc, Map.of("image_url", photo(it), "is_carousel_item", "true")));
            for (String child : children) waitReady(acc, child);
            container = create(acc, Map.of("media_type", "CAROUSEL", "children", String.join(",", children), "caption", caption));
        }
        waitReady(acc, container);
        JsonNode published = postForm(graphUrl + "/" + acc.igUserId() + "/media_publish",
            Map.of("creation_id", container, "access_token", acc.pageToken()));
        return published.path("id").asText();
    }

    private String create(Account acc, Map<String, String> params) {
        Map<String, String> form = new LinkedHashMap<>(params);
        form.put("access_token", acc.pageToken());
        String id = postForm(graphUrl + "/" + acc.igUserId() + "/media", form).path("id").asText("");
        if (id.isEmpty()) throw new IllegalStateException("Instagram did not create the media container");
        return id;
    }

    /** Containers are processed asynchronously: wait for FINISHED (up to ~60 s). */
    private void waitReady(Account acc, String containerId) throws InterruptedException {
        for (int i = 0; i < 30; i++) {
            String status = field(acc, containerId, "status_code");
            if ("FINISHED".equals(status)) return;
            if ("ERROR".equals(status) || "EXPIRED".equals(status)) {
                throw new IllegalStateException("Instagram could not process a photo (" + status + ")");
            }
            Thread.sleep(2000);
        }
        throw new IllegalStateException("Instagram is still processing the photos - try again in a minute");
    }

    private String field(Account acc, String id, String field) {
        return get(graphUrl + "/" + id + "?fields=" + field + "&access_token=" + enc(acc.pageToken())).path(field).asText(null);
    }

    static List<List<Map<String, Object>>> chunks(List<Map<String, Object>> items) {
        List<List<Map<String, Object>>> out = new ArrayList<>();
        for (int i = 0; i < items.size(); i += CAROUSEL_MAX) out.add(items.subList(i, Math.min(i + CAROUSEL_MAX, items.size())));
        // avoid a lonely last post of 1: move one photo over (e.g. 11 -> 9 + 2)
        if (out.size() > 1 && out.get(out.size() - 1).size() == 1) {
            List<Map<String, Object>> prev = new ArrayList<>(out.get(out.size() - 2));
            List<Map<String, Object>> last = new ArrayList<>(out.get(out.size() - 1));
            last.add(0, prev.remove(prev.size() - 1));
            out.set(out.size() - 2, prev);
            out.set(out.size() - 1, last);
        }
        return out;
    }

    String caption(Map<String, Object> c, List<Map<String, Object>> items, int part, int parts) {
        StringBuilder s = new StringBuilder();
        s.append("✨ ").append(c.get("name")).append(" ✨\n");
        if (c.get("description") != null && !String.valueOf(c.get("description")).isBlank()) s.append(c.get("description")).append('\n');
        BigDecimal price = items.stream().map(i -> i.get("price")).filter(Objects::nonNull)
            .map(p -> new BigDecimal(p.toString())).min(BigDecimal::compareTo).orElse(null);
        if (price != null) {
            s.append("\n💰 ₹").append(price.setScale(0, RoundingMode.HALF_UP).toPlainString())
                .append(Boolean.FALSE.equals(c.get("shipping_extra")) ? "" : " + shipping").append('\n');
        }
        s.append("🎨 ").append(items.size()).append(" colours in this post")
            .append(parts > 1 ? " (" + part + "/" + parts + ")" : "").append(":\n");
        for (Map<String, Object> it : items) {
            s.append("• ").append(it.get("item_code")).append(it.get("colour") == null ? "" : " – " + it.get("colour")).append('\n');
        }
        s.append("\n📲 To order, tap Chat on WhatsApp or message +").append(shopWhatsapp)
            .append(" with the code, e.g. ").append(items.get(0).get("item_code")).append('\n');
        s.append("Code: ").append(c.get("code")).append("\n\n");
        s.append(hashtags((Integer) c.get("collection_id")));
        String out = s.toString();
        return out.length() > 2150 ? out.substring(0, 2150) : out;
    }

    private String hashtags(int collectionId) {
        List<?> rows = tx.execute(st -> em.createNativeQuery("""
                SELECT CAST(extracted AS jsonb) -> 'hashtags' FROM wa_intake_batch
                 WHERE collection_id = ?1 AND extracted IS NOT NULL ORDER BY batch_id DESC LIMIT 1""")
            .setParameter(1, collectionId).getResultList());
        List<String> tags = new ArrayList<>();
        try {
            if (!rows.isEmpty() && rows.get(0) != null) {
                for (JsonNode n : json.readTree(rows.get(0).toString())) {
                    String t = n.asText("").trim().replace(" ", "");
                    if (!t.isEmpty()) tags.add(t.startsWith("#") ? t : "#" + t);
                }
            }
        } catch (Exception ignored) {
            // hashtags are optional
        }
        tags.addAll(List.of("#srisasarees", "#sarees"));
        return tags.stream().distinct().limit(25).collect(Collectors.joining(" "));
    }

    private void saveLink(String collectionCode, String mediaId, String permalink, String label) {
        tx.executeWithoutResult(s -> em.createNativeQuery("""
                INSERT INTO marketing_links (source_type, source_id, collection_code, label, active, permalink, created_by, created_at, updated_at)
                VALUES ('IG_MEDIA', ?1, ?2, ?3, TRUE, ?4, 'AUTO', NOW(), NOW())
                ON CONFLICT DO NOTHING""").unwrap(NativeQuery.class)
            .setParameter(1, mediaId, String.class).setParameter(2, collectionCode, String.class)
            .setParameter(3, label.length() > 150 ? label.substring(0, 150) : label, String.class)
            .setParameter(4, permalink, String.class).executeUpdate());
    }

    // ── Meta catalog ─────────────────────────────────────────────────────────

    /** One items_batch upsert: every variant, linked to a WhatsApp chat pre-filled with its code. */
    private int pushCatalog(Map<String, Object> c, List<Map<String, Object>> items) {
        if (catalogId == null || catalogId.isBlank() || accessToken == null || accessToken.isBlank()) return 0;
        List<Map<String, Object>> requests = new ArrayList<>();
        for (Map<String, Object> it : items) {
            if (it.get("price") == null) continue;
            String code = String.valueOf(it.get("item_code"));   // retailer id: dropship variants aren't in Products
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", code);
            data.put("title", (c.get("name") + " – " + Objects.toString(it.get("colour"), code)).replaceAll("\\s+", " ").trim());
            data.put("description", Objects.toString(c.get("description"), String.valueOf(c.get("name"))) + "\nCode " + code);
            data.put("brand", "SRISA");
            data.put("price", new BigDecimal(it.get("price").toString()).setScale(2, RoundingMode.HALF_UP).toPlainString() + " INR");
            data.put("availability", "in stock");
            data.put("condition", "new");
            data.put("link", "https://wa.me/" + shopWhatsapp + "?text=" + enc("Hi SRISA! I'd like " + code));
            data.put("image_link", photo(it));
            requests.add(Map.of("method", "UPDATE", "retailer_id", code, "data", data));
        }
        if (requests.isEmpty()) return 0;
        Map<String, Object> payload = Map.of("item_type", "PRODUCT_ITEM", "allow_upsert", true, "requests", requests);
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(graphUrl + "/" + catalogId + "/items_batch"))
                .header("Content-Type", "application/json").header("Authorization", "Bearer " + accessToken)
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload))).build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) throw new IllegalStateException("HTTP " + resp.statusCode() + ": " + abbreviate(resp.body()));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
        return requests.size();
    }

    // ── HTTP helpers ─────────────────────────────────────────────────────────

    private static String photo(Map<String, Object> item) {
        return String.valueOf(item.get("stamped_image_url") != null ? item.get("stamped_image_url") : item.get("image_url"));
    }

    private JsonNode get(String url) {
        return send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build());
    }

    private JsonNode postForm(String url, Map<String, String> form) {
        String body = form.entrySet().stream().map(e -> enc(e.getKey()) + "=" + enc(e.getValue())).collect(Collectors.joining("&"));
        return send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    private JsonNode send(HttpRequest req) {
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode body = json.readTree(resp.body());
            if (resp.statusCode() / 100 != 2 || body.has("error")) {
                throw new IllegalStateException(body.path("error").path("message").asText("HTTP " + resp.statusCode()));
            }
            return body;
        } catch (RuntimeException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        } catch (Exception e) {
            throw new IllegalStateException("Graph API call failed: " + e.getMessage(), e);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private static String abbreviate(String s) {
        return s == null ? "" : s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}

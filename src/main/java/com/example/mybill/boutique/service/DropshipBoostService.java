package com.example.mybill.boutique.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Boosting a dropship collection's Instagram post as a Click-to-WhatsApp ad (Meta Marketing API, META_ACCESS_TOKEN
 * with ads_management, ad account META_AD_ACCOUNT_ID). After a post goes up, the owner who forwarded it gets
 * "📣 Boost?" [₹200 × 1 day] [₹200 × 2 days] [No], then the audience [Hyderabad] [Telangana + AP] [All India].
 * The ad's "Send message" opens a WhatsApp chat with the shop; the ad id is saved as an AD marketing link to the
 * collection, so the agent opens that collection and orders are attributed to it. Never spends without a tap; at
 * most dropship.boost.monthly-cap (₹5,000) a calendar month (IST). STOP BOOST {code} pauses it; a daily summary
 * (spend, chats, orders) goes to the owner.
 */
@Service
public class DropshipBoostService {

    private static final Logger log = LoggerFactory.getLogger(DropshipBoostService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** Button ids: dsboost:{collectionId}:{days} (0 = no), dsaud:{collectionId}:{days}:{audience}. */
    public static final String BOOST = "dsboost:", AUDIENCE = "dsaud:";
    public static final List<String> AUDIENCES = List.of("HYDERABAD", "TS_AP", "INDIA");

    @PersistenceContext private EntityManager em;
    @Autowired private TransactionTemplate tx;
    @Autowired private ObjectMapper json;
    @Autowired private ApplicationEventPublisher events;
    @Autowired private DropshipPublishService publisher;
    @Autowired private OwnerNumberService ownerNumbers;

    @Value("${meta.access.token:}") private String accessToken;
    @Value("${meta.ads.account-id:}") private String adAccountId;
    @Value("${meta.ads.graph-url:https://graph.facebook.com/v23.0}") private String graphUrl;
    @Value("${dropship.boost.daily-budget:200}") private BigDecimal dailyBudget;
    @Value("${dropship.boost.monthly-cap:5000}") private BigDecimal monthlyCap;
    @Value("${dropship.boost.age-min:22}") private int ageMin;
    @Value("${dropship.boost.age-max:55}") private int ageMax;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final Map<String, String> regionKeys = new ConcurrentHashMap<>();

    public boolean configured() {
        return adAccountId != null && !adAccountId.isBlank() && accessToken != null && !accessToken.isBlank();
    }

    // ── WhatsApp buttons ─────────────────────────────────────────────────────

    /** "Boost it?" after a post goes up (only when an ad account is configured). */
    public Optional<DropshipIntakeService.OwnerMessage> offer(int collectionId, String code, String owner) {
        if (!configured() || owner == null) return Optional.empty();
        String b = dailyBudget.stripTrailingZeros().toPlainString();
        return Optional.of(new DropshipIntakeService.OwnerMessage(owner, "boost-offer:" + collectionId + ":" + UUID.randomUUID(),
            "buttons", "📣 Boost " + code + " on Instagram? People who tap it get a WhatsApp chat with us about " + code
                + ".\n₹" + b + " a day" + spentLine(), null, null, null, null,
            List.of(Map.of("id", BOOST + collectionId + ":1", "title", "₹" + b + " × 1 day"),
                Map.of("id", BOOST + collectionId + ":2", "title", "₹" + b + " × 2 days"),
                Map.of("id", BOOST + collectionId + ":0", "title", "No boost"))));
    }

    /** A tap on the boost / audience buttons, or STOP BOOST {code}. {handled, action, ...}; action "boost" → the agent calls /boost. */
    public Map<String, Object> tap(String owner, String replyId) {
        String[] f = replyId.split(":");
        int collectionId = Integer.parseInt(f[1]);
        int days = Integer.parseInt(f[2]);
        String code = code(collectionId);
        Optional<String> postedBy = ownerNumbers.ownerOfCollection(collectionId);
        if (postedBy.isPresent() && !postedBy.get().equals(owner)) {
            tell(owner, "ℹ️ " + code + " was forwarded from +" + postedBy.get() + " – only that number can boost it here.");
            return Map.of("handled", true, "action", "not_yours");
        }
        if (replyId.startsWith(BOOST)) {
            if (days <= 0) {
                tell(owner, "👍 " + code + " won't be boosted. Send *BOOST " + code + "* any time to boost it later.");
                return Map.of("handled", true, "action", "boost_declined");
            }
            return askAudience(owner, collectionId, code, days);
        }
        String audience = f.length > 3 ? f[3] : "";
        if (!AUDIENCES.contains(audience) || days < 1 || days > 2) return Map.of("handled", false, "reason", "bad_boost_tap");
        tell(owner, "⏳ Creating the boost for " + code + "…");
        return Map.of("handled", true, "action", "boost", "collection_id", collectionId, "days", days, "audience", audience);
    }

    /** BOOST {code}: the buttons again (e.g. after "No boost"). */
    public Map<String, Object> boostCommand(String owner, String code) {
        Integer id = collectionId(code);
        if (id == null) {
            tell(owner, "No collection " + code + ".");
            return Map.of("handled", true, "action", "unknown_collection");
        }
        Optional<DropshipIntakeService.OwnerMessage> m = offer(id, code(id), owner);
        if (m.isEmpty()) {
            tell(owner, "⚠️ Boosting isn't set up yet (no ad account in mybill).");
            return Map.of("handled", true, "action", "boost_not_configured");
        }
        events.publishEvent(m.get());
        return Map.of("handled", true, "action", "boost_offer");
    }

    private Map<String, Object> askAudience(String owner, int collectionId, String code, int days) {
        events.publishEvent(new DropshipIntakeService.OwnerMessage(owner, "boost-aud:" + collectionId + ":" + UUID.randomUUID(),
            "buttons", "Who should see " + code + "? (₹" + total(days).toPlainString() + " for " + days + (days == 1 ? " day" : " days")
                + ", women " + ageMin + "–" + ageMax + ")", null, null, null, null,
            List.of(Map.of("id", AUDIENCE + collectionId + ":" + days + ":HYDERABAD", "title", "Hyderabad"),
                Map.of("id", AUDIENCE + collectionId + ":" + days + ":TS_AP", "title", "Telangana + AP"),
                Map.of("id", AUDIENCE + collectionId + ":" + days + ":INDIA", "title", "All India"))));
        return Map.of("handled", true, "action", "boost_audience");
    }

    // ── creating the ad ──────────────────────────────────────────────────────

    /** Campaign → ad set (lifetime budget, WhatsApp destination) → creative from the IG post → ad. Tells the owner. */
    public Map<String, Object> boost(int collectionId, int days, String audience, String owner) {
        String code = code(collectionId);
        String to = owner != null ? owner : ownerNumbers.ownerOfCollection(collectionId).orElse(null);
        BigDecimal total = total(days);
        if (!configured()) return fail(to, null, code, "boosting isn't set up (META_AD_ACCOUNT_ID)");
        BigDecimal used = monthSpend();
        if (used.add(total).compareTo(monthlyCap) > 0) {
            return fail(to, null, code, "this month's boost limit is ₹" + monthlyCap.toPlainString() + " and ₹" + used.toPlainString()
                + " is already used – not boosted");
        }
        Long running = tx.execute(s -> ((Number) em.createNativeQuery("""
                SELECT COUNT(*) FROM dropship_boosts WHERE collection_id = ?1 AND status IN ('CREATING', 'ACTIVE') AND ends_at > NOW()""")
            .setParameter(1, collectionId).getSingleResult()).longValue());
        if (running != null && running > 0) return fail(to, null, code, "it is already being boosted");
        String mediaId = tx.execute(s -> {
            List<?> r = em.createNativeQuery("""
                    SELECT source_id FROM marketing_links WHERE source_type = 'IG_MEDIA' AND collection_code = ?1
                     ORDER BY created_at DESC LIMIT 1""").setParameter(1, code).getResultList();
            return r.isEmpty() ? null : String.valueOf(r.get(0));
        });
        if (mediaId == null) return fail(to, null, code, "it isn't on Instagram yet – approve and post it first");

        Instant start = Instant.now(), end = start.plus(Duration.ofDays(days));
        int boostId = tx.execute(s -> ((Number) em.createNativeQuery("""
                INSERT INTO dropship_boosts (collection_id, collection_code, owner_phone, days, daily_budget, total, audience,
                                             media_id, starts_at, ends_at)
                VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10) RETURNING boost_id""")
            .setParameter(1, collectionId).setParameter(2, code).setParameter(3, to).setParameter(4, days)
            .setParameter(5, dailyBudget).setParameter(6, total).setParameter(7, audience).setParameter(8, mediaId)
            .setParameter(9, Timestamp.from(start)).setParameter(10, Timestamp.from(end)).getSingleResult()).intValue());

        String campaignId = null;
        try {
            DropshipPublishService.Account acc = publisher.account();
            String name = "SRISA " + code + " boost #" + boostId;
            campaignId = post("/" + act() + "/campaigns", Map.of("name", name, "objective", "OUTCOME_ENGAGEMENT",
                "status", "ACTIVE", "special_ad_categories", "[]", "is_adset_budget_sharing_enabled", "false"));
            Map<String, String> adset = new LinkedHashMap<>();
            adset.put("name", name);
            adset.put("campaign_id", campaignId);
            adset.put("lifetime_budget", total.movePointRight(2).toBigInteger().toString());   // paise
            adset.put("start_time", String.valueOf(start.getEpochSecond()));
            adset.put("end_time", String.valueOf(end.getEpochSecond()));
            adset.put("billing_event", "IMPRESSIONS");
            adset.put("optimization_goal", "CONVERSATIONS");
            adset.put("destination_type", "WHATSAPP");
            adset.put("bid_strategy", "LOWEST_COST_WITHOUT_CAP");
            adset.put("promoted_object", json.writeValueAsString(Map.of("page_id", acc.pageId())));
            adset.put("targeting", json.writeValueAsString(targeting(audience)));
            adset.put("status", "ACTIVE");
            String adsetId = post("/" + act() + "/adsets", adset);
            String creativeId = post("/" + act() + "/adcreatives", Map.of("name", name,
                "object_id", acc.pageId(), "instagram_user_id", acc.igUserId(), "source_instagram_media_id", mediaId,
                "call_to_action", json.writeValueAsString(Map.of("type", "WHATSAPP_MESSAGE", "value", Map.of("app_destination", "WHATSAPP")))));
            String adId = post("/" + act() + "/ads", Map.of("name", name, "adset_id", adsetId,
                "creative", json.writeValueAsString(Map.of("creative_id", creativeId)), "status", "ACTIVE"));
            String cid = campaignId;
            tx.executeWithoutResult(s -> {
                em.createNativeQuery("""
                        UPDATE dropship_boosts SET status = 'ACTIVE', campaign_id = ?1, adset_id = ?2, creative_id = ?3, ad_id = ?4,
                               updated_at = NOW() WHERE boost_id = ?5""")
                    .setParameter(1, cid).setParameter(2, adsetId).setParameter(3, creativeId).setParameter(4, adId)
                    .setParameter(5, boostId).executeUpdate();
                em.createNativeQuery("""
                        INSERT INTO marketing_links (source_type, source_id, collection_code, label, active, created_by, created_at, updated_at)
                        VALUES ('AD', ?1, ?2, ?3, TRUE, 'AUTO', NOW(), NOW()) ON CONFLICT DO NOTHING""")
                    .setParameter(1, adId).setParameter(2, code).setParameter(3, "Boost " + code + " #" + boostId).executeUpdate();
            });
            log.info("DROPSHIP_BOOST collection={} boost={} ad={} days={} audience={}", code, boostId, adId, days, audience);
            tell(to, "✅ " + code + " boost created: ₹" + total.toPlainString() + " for " + days + (days == 1 ? " day" : " days")
                + ", " + audienceName(audience) + ". Meta reviews it first (usually minutes, sometimes a few hours), then it starts."
                + "\nTo stop it early: *STOP BOOST " + code + "*");
            return Map.of("boosted", true, "boost_id", boostId, "ad_id", adId);
        } catch (Exception e) {
            log.error("DROPSHIP_BOOST_FAILED collection={} boost={}: {}", code, boostId, e.getMessage());
            if (campaignId != null) {
                try { send("DELETE", "/" + campaignId, Map.of()); } catch (Exception ignored) { /* best effort */ }
            }
            return fail(to, boostId, code, e.getMessage());
        }
    }

    /** STOP BOOST {code}: pause the running campaign. */
    public Map<String, Object> stop(String owner, String code) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = tx.execute(s -> em.createNativeQuery("""
                SELECT boost_id, campaign_id, owner_phone FROM dropship_boosts
                 WHERE UPPER(collection_code) = UPPER(?1) AND status = 'ACTIVE' ORDER BY boost_id DESC""")
            .setParameter(1, code).getResultList());
        if (rows == null || rows.isEmpty()) {
            tell(owner, "No running boost for " + code.toUpperCase(Locale.ROOT) + ".");
            return Map.of("handled", true, "action", "no_boost");
        }
        int stopped = 0;
        for (Object[] r : rows) {
            try {
                post("/" + r[1], Map.of("status", "PAUSED"));
                tx.executeWithoutResult(s -> em.createNativeQuery(
                        "UPDATE dropship_boosts SET status = 'STOPPED', updated_at = NOW() WHERE boost_id = ?1")
                    .setParameter(1, r[0]).executeUpdate());
                stopped++;
            } catch (Exception e) {
                tell(owner, "⚠️ Couldn't stop the " + code.toUpperCase(Locale.ROOT) + " boost: " + e.getMessage() + " – pause it in Ads Manager.");
            }
        }
        if (stopped > 0) tell(owner, "⏹️ " + code.toUpperCase(Locale.ROOT) + " boost stopped.");
        return Map.of("handled", true, "action", "boost_stopped", "stopped", stopped);
    }

    // ── daily summary ────────────────────────────────────────────────────────

    /** Every running boost, and ones that ended since the last summary: spend, chats, orders → the owner. */
    public int dailySummary() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = tx.execute(s -> em.createNativeQuery("""
                SELECT boost_id, collection_code, owner_phone, campaign_id, ad_id, total, ends_at, status FROM dropship_boosts
                 WHERE campaign_id IS NOT NULL AND (status = 'ACTIVE' OR (status IN ('STOPPED', 'ENDED') AND summary_sent_at IS NULL))""")
            .getResultList());
        int sent = 0;
        for (Object[] r : rows == null ? List.<Object[]>of() : rows) {
            int boostId = ((Number) r[0]).intValue();
            String code = (String) r[1], adId = (String) r[4];
            boolean over = r[6] != null && ((Timestamp) r[6]).toInstant().isBefore(Instant.now());
            try {
                JsonNode in = get("/" + r[3] + "/insights?date_preset=maximum&fields=spend,impressions,reach,actions").path("data").path(0);
                BigDecimal spend = new BigDecimal(in.path("spend").asText("0"));
                long chats = 0;
                for (JsonNode a : in.path("actions")) {
                    if (a.path("action_type").asText().contains("messaging_conversation_started")) chats += a.path("value").asLong();
                }
                long orders = tx.execute(s -> ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM boutique_orders WHERE source_type = 'AD' AND source_id = ?1")
                    .setParameter(1, adId).getSingleResult()).longValue());
                boolean finished = over || !"ACTIVE".equals(r[7]);
                tx.executeWithoutResult(s -> em.createNativeQuery("""
                        UPDATE dropship_boosts SET spend = ?1, updated_at = NOW(),
                               status = CASE WHEN status = 'ACTIVE' AND ?2 THEN 'ENDED' ELSE status END,
                               summary_sent_at = CASE WHEN ?3 THEN NOW() ELSE summary_sent_at END WHERE boost_id = ?4""")
                    .setParameter(1, spend).setParameter(2, over).setParameter(3, finished).setParameter(4, boostId).executeUpdate());
                tell((String) r[2], "📣 " + code + " boost" + (finished ? " (finished)" : " so far") + ": spent ₹" + spend.toPlainString()
                    + " of ₹" + ((BigDecimal) r[5]).toPlainString() + ", " + in.path("reach").asLong() + " people reached, "
                    + chats + " WhatsApp chat(s), " + orders + " order(s)." + (finished ? "" : "\nStop: *STOP BOOST " + code + "*"));
                sent++;
            } catch (Exception e) {
                log.warn("Boost summary failed for {}: {}", boostId, e.getMessage());
            }
        }
        return sent;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Map<String, Object> targeting(String audience) throws Exception {
        Map<String, Object> geo = new LinkedHashMap<>();
        switch (audience) {
            case "HYDERABAD" -> geo.put("custom_locations", List.of(Map.of("latitude", 17.385, "longitude", 78.4867,
                "radius", 40, "distance_unit", "kilometer")));
            case "TS_AP" -> geo.put("regions", List.of(Map.of("key", region("Telangana")), Map.of("key", region("Andhra Pradesh"))));
            default -> geo.put("countries", List.of("IN"));
        }
        geo.put("location_types", List.of("home", "recent"));
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("geo_locations", geo);
        t.put("age_min", ageMin);
        t.put("age_max", ageMax);
        t.put("genders", List.of(2));
        t.put("publisher_platforms", List.of("instagram"));
        t.put("targeting_automation", Map.of("advantage_audience", 0));
        return t;
    }

    /** Meta's key for an Indian state (looked up once). */
    private String region(String name) {
        return regionKeys.computeIfAbsent(name, n -> {
            JsonNode r = get("/search?type=adgeolocation&location_types=" + enc("[\"region\"]") + "&country_code=IN&q=" + enc(n));
            for (JsonNode x : r.path("data")) {
                if (x.path("name").asText().equalsIgnoreCase(n)) return x.path("key").asText();
            }
            throw new IllegalStateException("Meta has no region " + n);
        });
    }

    /** Committed this calendar month (IST): budgets of boosts, or what a stopped one actually spent. */
    private BigDecimal monthSpend() {
        Timestamp from = Timestamp.from(LocalDate.now(IST).withDayOfMonth(1).atStartOfDay(IST).toInstant());
        Object v = tx.execute(s -> em.createNativeQuery("""
                SELECT COALESCE(SUM(CASE WHEN status = 'STOPPED' AND spend IS NOT NULL THEN spend ELSE total END), 0)
                  FROM dropship_boosts WHERE status <> 'FAILED' AND created_at >= ?1""").setParameter(1, from).getSingleResult());
        return new BigDecimal(String.valueOf(v));
    }

    private String spentLine() {
        BigDecimal used = monthSpend();
        return used.signum() == 0 ? "" : " (₹" + used.toPlainString() + " of this month's ₹" + monthlyCap.toPlainString() + " used)";
    }

    private Map<String, Object> fail(String to, Integer boostId, String code, String why) {
        if (boostId != null) {
            tx.executeWithoutResult(s -> em.createNativeQuery(
                    "UPDATE dropship_boosts SET status = 'FAILED', error = ?1, updated_at = NOW() WHERE boost_id = ?2")
                .setParameter(1, why).setParameter(2, boostId).executeUpdate());
        }
        tell(to, "⚠️ " + code + " was not boosted: " + why);
        return Map.of("boosted", false, "error", why);
    }

    private void tell(String to, String text) {
        if (to == null) return;
        events.publishEvent(new DropshipIntakeService.OwnerMessage(to, "boost:" + UUID.randomUUID(), "text", text, null, null, null, null));
    }

    private BigDecimal total(int days) {
        return dailyBudget.multiply(BigDecimal.valueOf(days)).stripTrailingZeros().setScale(0, java.math.RoundingMode.HALF_UP);
    }

    private static String audienceName(String a) {
        return switch (a) {
            case "HYDERABAD" -> "Hyderabad + 40 km";
            case "TS_AP" -> "Telangana + Andhra Pradesh";
            default -> "all India";
        };
    }

    private String code(int collectionId) {
        return tx.execute(s -> {
            List<?> r = em.createNativeQuery("SELECT code FROM boutique_collections WHERE collection_id = ?1").setParameter(1, collectionId).getResultList();
            return r.isEmpty() ? "#" + collectionId : String.valueOf(r.get(0));
        });
    }

    private Integer collectionId(String code) {
        return tx.execute(s -> {
            List<?> r = em.createNativeQuery("SELECT collection_id FROM boutique_collections WHERE UPPER(code) = UPPER(?1) AND collection_type = 'DROPSHIP'")
                .setParameter(1, code).getResultList();
            return r.isEmpty() ? null : ((Number) r.get(0)).intValue();
        });
    }

    private String act() {
        return adAccountId.startsWith("act_") ? adAccountId : "act_" + adAccountId.replaceAll("\\D", "");
    }

    private String post(String path, Map<String, String> form) {
        return send("POST", path, form).path("id").asText(null);
    }

    private JsonNode get(String pathAndQuery) {
        return send("GET", pathAndQuery, Map.of());
    }

    private JsonNode send(String method, String path, Map<String, String> form) {
        try {
            String body = form.entrySet().stream().map(e -> enc(e.getKey()) + "=" + enc(e.getValue())).collect(Collectors.joining("&"));
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(graphUrl + path)).timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + accessToken);
            if ("GET".equals(method)) b.GET();
            else b.header("Content-Type", "application/x-www-form-urlencoded").method(method, HttpRequest.BodyPublishers.ofString(body));
            HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode r = json.readTree(resp.body());
            if (resp.statusCode() / 100 != 2 || r.has("error")) {
                JsonNode err = r.path("error");
                String msg = err.path("error_user_msg").asText(err.path("message").asText("HTTP " + resp.statusCode()));
                throw new IllegalStateException(msg);
            }
            return r;
        } catch (RuntimeException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        } catch (Exception e) {
            throw new IllegalStateException("Meta Ads call failed: " + e.getMessage(), e);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}

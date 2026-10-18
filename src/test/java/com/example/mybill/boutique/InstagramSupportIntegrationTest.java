package com.example.mybill.boutique;

import com.example.mybill.boutique.config.AgentTokenVerifier;
import com.example.mybill.boutique.config.AgentTokenVerifier.VerifiedToken;
import com.example.mybill.boutique.config.BoutiqueSchemaInitializer;
import com.example.mybill.wholesale.AbstractFirmIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Instagram support: customer merge on upsert, WhatsApp consent, IG media sync, resolve by permalink.
 * Real PostgreSQL (Testcontainers); skipped without Docker.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = {
    "agent.auth.enabled=true",
    "agent.api.audience=https://mybill-service.test",
    "agent.allowed-sa-email=srisa-agent-sa@test.iam.gserviceaccount.com",
    "agent.tenant-schema=firm_igapi",
    "boutique.delivery.flat-charge=100",
    "boutique.delivery.free-above=2500"
})
class InstagramSupportIntegrationTest extends AbstractFirmIntegrationTest {

    private static final String AGENT = "Bearer agent-token";

    @MockBean private AgentTokenVerifier tokenVerifier;
    @Autowired private BoutiqueSchemaInitializer boutiqueSchemaInitializer;

    private final ObjectMapper json = new ObjectMapper();
    private final AtomicInteger keys = new AtomicInteger();
    private FirmCtx firm;
    private String s;   // "<schema>".

    @BeforeAll
    void setUp() {
        firm = registerFirm("igapi");
        boutiqueSchemaInitializer.ensureSchema(firm.schema());
        s = "\"" + firm.schema() + "\".";
        jdbc.update("INSERT INTO " + s + "boutique_designs (code, name, garment_type, rough_price, delivery_days, is_active, "
            + "created_at) VALUES ('KUR-401', 'Mustard Kurti', 'Kurta', 1500, 7, TRUE, NOW()), "
            + "('KUR-402', 'Rose Anarkali', 'Kurta', 2000, 7, TRUE, NOW())");
        tokens();
    }

    @BeforeEach
    void tokens() {
        when(tokenVerifier.verify(anyString(), anyString())).thenReturn(Optional.empty());
        when(tokenVerifier.verify(eq("agent-token"), eq("https://mybill-service.test"))).thenReturn(
            Optional.of(new VerifiedToken("https://accounts.google.com", "srisa-agent-sa@test.iam.gserviceaccount.com", true)));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private ResultActions agent(MockHttpServletRequestBuilder r) throws Exception {
        return mvc.perform(r.header("Authorization", AGENT));
    }

    private ResultActions agentPost(String path, String body) throws Exception {
        return agent(post(path).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private int upsert(String fields) throws Exception {
        return body(agentPost("/api/agent/customers/upsert", "{" + fields + "}")).get("customer_id").asInt();
    }

    private String order(int customerId) throws Exception {
        String req = """
            {"customer_id":%d,"design_code":"KUR-401","size_code":"M","option_ids":[],"qty":1,
             "delivery":{"name":"Ig Tester","phone":"+91 98000 00001","address_line1":"1 Ig Rd",
                         "city":"Hyderabad","state":"Telangana","pincode":"500001"},
             "channel":"INSTAGRAM","idempotency_key":"ig-%d"}""".formatted(customerId, keys.incrementAndGet());
        return body(agentPost("/api/agent/orders", req).andExpect(status().isCreated())).get("order_no").asText();
    }

    private void measurement(int customerId) throws Exception {
        agentPost("/api/agent/customers/" + customerId + "/measurements",
            "{\"size_code\":\"M\",\"bust_in\":36,\"waist_in\":30,\"hip_in\":39}").andExpect(status().isCreated());
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    // ── 1. merge on upsert ───────────────────────────────────────────────────

    @Test
    void upsert_mergesPhoneAndInstagramCustomers_keepsOlder() throws Exception {
        int phoneCustomer = upsert("\"phone_e164\":\"+919800000001\",\"name\":\"Asha (WhatsApp)\"");
        int igCustomer = upsert("\"instagram_user_id\":\"ig_merge_1\",\"name\":\"asha.styles\"");
        assertThat(igCustomer).isGreaterThan(phoneCustomer);
        measurement(phoneCustomer);
        measurement(igCustomer);
        String igOrder = order(igCustomer);
        agentPost("/api/agent/marketing-events", "{\"source_type\":\"IG_MEDIA\",\"source_id\":\"1\",\"customer_id\":"
            + igCustomer + "}").andExpect(status().isCreated());

        JsonNode merged = body(agentPost("/api/agent/customers/upsert",
            "{\"phone_e164\":\"+919800000001\",\"instagram_user_id\":\"ig_merge_1\",\"name\":\"Asha\"}")
            .andExpect(status().isOk()));
        assertThat(merged.get("customer_id").asInt()).isEqualTo(phoneCustomer);
        assertThat(merged.get("merged_customer_id").asInt()).isEqualTo(igCustomer);
        assertThat(merged.get("phone_e164").asText()).isEqualTo("+919800000001");
        assertThat(merged.get("instagram_user_id").asText()).isEqualTo("ig_merge_1");
        assertThat(merged.get("name").asText()).isEqualTo("Asha (WhatsApp)");   // kept customer's name wins
        assertThat(merged.get("created").asBoolean()).isFalse();

        assertThat(count("SELECT COUNT(*) FROM " + s + "customers WHERE customer_id = ?", igCustomer)).isZero();
        assertThat(count("SELECT COUNT(*) FROM " + s + "customer_measurements WHERE customer_id = ?", phoneCustomer)).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM " + s + "marketing_events WHERE customer_id = ?", phoneCustomer)).isEqualTo(1);
        agent(get("/api/agent/orders/" + igOrder)).andExpect(jsonPath("$.customer_id").value(phoneCustomer));
        agent(get("/api/agent/customers/" + phoneCustomer + "/measurements")).andExpect(status().isOk());

        var audit = jdbc.queryForMap("SELECT kept_customer_id, merged_customer_id, instagram_user_id, moved::text AS moved, "
            + "merged_customer->>'instagram_user_id' AS old_ig, reason FROM " + s + "customer_merges WHERE merged_customer_id = ?",
            igCustomer);
        assertThat(audit.get("kept_customer_id")).isEqualTo(phoneCustomer);
        assertThat(audit.get("merged_customer_id")).isEqualTo(igCustomer);
        assertThat(audit.get("old_ig")).isEqualTo("ig_merge_1");
        assertThat(audit.get("reason")).isEqualTo("AGENT_UPSERT_PHONE_AND_INSTAGRAM");
        JsonNode moved = json.readTree((String) audit.get("moved"));
        assertThat(moved.get("customer_measurements").asInt()).isEqualTo(1);
        assertThat(moved.get("boutique_orders").asInt()).isEqualTo(1);
        assertThat(moved.get("marketing_events").asInt()).isEqualTo(1);

        // Same identities again: nothing left to merge.
        JsonNode again = body(agentPost("/api/agent/customers/upsert",
            "{\"phone_e164\":\"+919800000001\",\"instagram_user_id\":\"ig_merge_1\"}").andExpect(status().isOk()));
        assertThat(again.get("customer_id").asInt()).isEqualTo(phoneCustomer);
        assertThat(again.get("merged_customer_id").isNull()).isTrue();
    }

    @Test
    void upsert_merge_keepsOlderEvenWhenItIsTheInstagramCustomer() throws Exception {
        int igCustomer = upsert("\"instagram_user_id\":\"ig_merge_2\"");
        int phoneCustomer = upsert("\"phone_e164\":\"+919800000002\",\"name\":\"Bindu\"");
        JsonNode merged = body(agentPost("/api/agent/customers/upsert",
            "{\"phone_e164\":\"+919800000002\",\"instagram_user_id\":\"ig_merge_2\"}").andExpect(status().isOk()));
        assertThat(merged.get("customer_id").asInt()).isEqualTo(igCustomer);
        assertThat(merged.get("merged_customer_id").asInt()).isEqualTo(phoneCustomer);
        assertThat(merged.get("phone_e164").asText()).isEqualTo("+919800000002");
        assertThat(merged.get("name").asText()).isEqualTo("Bindu");   // filled from the merged customer
    }

    @Test
    void upsert_conflictingIdentities_areNotMerged() throws Exception {
        int a = upsert("\"phone_e164\":\"+919800000003\",\"instagram_user_id\":\"ig_conflict_a\"");
        int b = upsert("\"instagram_user_id\":\"ig_conflict_b\"");
        agentPost("/api/agent/customers/upsert", "{\"phone_e164\":\"+919800000003\",\"instagram_user_id\":\"ig_conflict_b\"}")
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDENTITY_CONFLICT"));
        assertThat(count("SELECT COUNT(*) FROM " + s + "customers WHERE customer_id IN (?, ?)", a, b)).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM " + s + "customer_merges WHERE merged_customer_id IN (?, ?)", a, b)).isZero();
    }

    // ── 2. consent ───────────────────────────────────────────────────────────

    @Test
    void consent_isStoredWithTime() throws Exception {
        int id = upsert("\"phone_e164\":\"+919800000010\"");
        agentPost("/api/agent/customers/" + id + "/consent", "{\"whatsapp_updates\":true}").andExpect(status().isOk())
            .andExpect(jsonPath("$.whatsapp_updates_consent").value(true))
            .andExpect(jsonPath("$.consent_at").isNotEmpty());
        agentPost("/api/agent/customers/" + id + "/consent", "{\"whatsapp_updates\":false}").andExpect(status().isOk())
            .andExpect(jsonPath("$.whatsapp_updates_consent").value(false));
        assertThat(jdbc.queryForObject("SELECT whatsapp_updates_consent FROM " + s + "customers WHERE customer_id = ?",
            Boolean.class, id)).isFalse();
        agentPost("/api/agent/customers/" + id + "/consent", "{}").andExpect(status().isBadRequest());
        agentPost("/api/agent/customers/99999999/consent", "{\"whatsapp_updates\":true}")
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CUSTOMER_NOT_FOUND"));
        // New customers have never been asked.
        int fresh = upsert("\"phone_e164\":\"+919800000011\"");
        assertThat(jdbc.queryForObject("SELECT whatsapp_updates_consent FROM " + s + "customers WHERE customer_id = ?",
            Boolean.class, fresh)).isNull();
    }

    // ── 3 + 4. IG media sync and resolve by permalink ────────────────────────

    @Test
    void syncIgMedia_createsUpdatesSkips_andResolvesByPermalink() throws Exception {
        // A link staff created by hand for post 9001.
        mvc.perform(post("/api/boutique-marketing/links").header("Authorization", firm.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"source_type":"IG_MEDIA","source_id":"9001","design_code":"KUR-401","label":"Staff label"}"""))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.created_by").value("MANUAL"));

        String items = """
            [{"media_id":"8001","permalink":"https://www.instagram.com/p/AbC123/","media_type":"IMAGE",
              "caption":"Festive pick ✨ also loving KUR-402 but this one is Code: KUR-401 — DM to order!",
              "timestamp":"2026-10-18T05:00:00+0000"},
             {"media_id":"8002","permalink":"https://www.instagram.com/reel/XyZ789/","media_type":"VIDEO",
              "caption":"Rose Anarkali KUR-402 in pink"},
             {"media_id":"8003","caption":"Just a mood board, no codes"},
             {"media_id":"8004","caption":"Code: ZZZ-999 coming soon"},
             {"media_id":"not-a-number","caption":"KUR-401"},
             {"media_id":"9001","caption":"KUR-402 repost"}]""";
        JsonNode r = body(agentPost("/api/agent/marketing-links/sync-ig-media", items).andExpect(status().isOk()));
        assertThat(r.get("created").asInt()).isEqualTo(2);
        assertThat(r.get("updated").asInt()).isZero();
        assertThat(r.get("skipped").asInt()).isEqualTo(4);
        assertThat(r.get("items").get(0).get("design_code").asText()).isEqualTo("KUR-401");   // "Code:" wins
        assertThat(r.get("items").get(2).get("reason").asText()).isEqualTo("no_design_code");
        assertThat(r.get("items").get(3).get("reason").asText()).isEqualTo("design_not_found");
        assertThat(r.get("items").get(4).get("reason").asText()).isEqualTo("invalid_media_id");
        assertThat(r.get("items").get(5).get("reason").asText()).isEqualTo("manual_link");

        var auto = jdbc.queryForMap("SELECT design_code, label, permalink, created_by FROM " + s
            + "marketing_links WHERE source_type = 'IG_MEDIA' AND source_id = '8001'");
        assertThat(auto.get("created_by")).isEqualTo("AUTO");
        assertThat(auto.get("permalink")).isEqualTo("https://instagram.com/p/AbC123");
        assertThat(((String) auto.get("label")).codePointCount(0, ((String) auto.get("label")).length())).isEqualTo(60);
        assertThat(jdbc.queryForObject("SELECT label FROM " + s + "marketing_links WHERE source_id = '9001'", String.class))
            .isEqualTo("Staff label");   // manual link untouched

        // Re-sync: unchanged is skipped; a changed caption updates the AUTO link.
        JsonNode again = body(agentPost("/api/agent/marketing-links/sync-ig-media", """
            [{"media_id":"8002","permalink":"https://www.instagram.com/reel/XyZ789/","caption":"Rose Anarkali KUR-402 in pink"},
             {"media_id":"8001","caption":"Now restocked: Code: KUR-402"}]""").andExpect(status().isOk()));
        assertThat(again.get("updated").asInt()).isEqualTo(1);
        assertThat(again.get("items").get(0).get("reason").asText()).isEqualTo("unchanged");
        assertThat(jdbc.queryForObject("SELECT design_code FROM " + s + "marketing_links WHERE source_id = '8001'", String.class))
            .isEqualTo("KUR-402");

        // Resolve by permalink: www / query / trailing slash don't matter.
        agent(get("/api/agent/marketing-links/resolve").param("type", "IG_MEDIA")
                .param("permalink", "https://instagram.com/p/AbC123?igsh=MWQ1ZGUxMzBkMA=="))
            .andExpect(status().isOk()).andExpect(jsonPath("$.design_code").value("KUR-402"));
        agent(get("/api/agent/marketing-links/resolve").param("type", "IG_MEDIA").param("id", "8002"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.design_code").value("KUR-402"));
        agent(get("/api/agent/marketing-links/resolve").param("type", "IG_MEDIA")
                .param("permalink", "https://www.instagram.com/p/Unknown1/"))
            .andExpect(status().isNotFound());
        agent(get("/api/agent/marketing-links/resolve").param("type", "IG_MEDIA")).andExpect(status().isBadRequest());

        // Deactivated by staff: no longer resolves, and the sync doesn't re-activate it.
        Integer linkId = jdbc.queryForObject("SELECT link_id FROM " + s + "marketing_links WHERE source_id = '8002'", Integer.class);
        mvc.perform(post("/api/boutique-marketing/links/" + linkId + "/deactivate").header("Authorization", firm.bearer()))
            .andExpect(status().isOk());
        agent(get("/api/agent/marketing-links/resolve").param("type", "IG_MEDIA")
                .param("permalink", "https://www.instagram.com/reel/XyZ789/")).andExpect(status().isNotFound());
        agentPost("/api/agent/marketing-links/sync-ig-media",
            "[{\"media_id\":\"8002\",\"caption\":\"Rose Anarkali KUR-401 relaunch\"}]").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT active FROM " + s + "marketing_links WHERE source_id = '8002'", Boolean.class))
            .isFalse();
    }
}

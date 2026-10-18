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

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Click-to-WhatsApp ad attribution: marketing links (admin), resolve + events (agent), order source fields and the
 * "Ad performance" report, against a real PostgreSQL (Testcontainers). Skipped without Docker.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(properties = {
    "agent.auth.enabled=true",
    "agent.api.audience=https://mybill-service.test",
    "agent.allowed-sa-email=srisa-agent-sa@test.iam.gserviceaccount.com",
    "agent.tenant-schema=firm_mktapi",
    "boutique.delivery.flat-charge=100",
    "boutique.delivery.free-above=2500"
})
class MarketingAttributionIntegrationTest extends AbstractFirmIntegrationTest {

    private static final String AGENT = "Bearer agent-token";
    private static final String AD_ACTIVE = "120210000000000001";
    private static final String AD_INACTIVE = "120210000000000002";
    private static final String AD_REPORT = "120210000000000003";
    private static final String AD_UNMAPPED = "120210000000009999";

    @MockBean private AgentTokenVerifier tokenVerifier;
    @Autowired private BoutiqueSchemaInitializer boutiqueSchemaInitializer;

    private final ObjectMapper json = new ObjectMapper();
    private final AtomicInteger keys = new AtomicInteger();
    private FirmCtx firm;
    private int customerId;

    @BeforeAll
    void setUp() throws Exception {
        firm = registerFirm("mktapi");
        boutiqueSchemaInitializer.ensureSchema(firm.schema());
        jdbc.update("INSERT INTO \"" + firm.schema() + "\".boutique_designs (code, name, garment_type, rough_price, delivery_days, "
            + "is_active, created_at) VALUES ('KUR-301', 'Mustard Kurti', 'Kurta', 1500.00, 7, TRUE, NOW()), "
            + "('KUR-302', 'Rose Anarkali', 'Kurta', 2000.00, 7, TRUE, NOW())");
        tokens();
        customerId = json.readTree(agentPost("/api/agent/customers/upsert", "{\"phone_e164\":\"+919822233344\",\"name\":\"Ad Tester\"}")
            .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).get("customer_id").asInt();
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

    private ResultActions admin(MockHttpServletRequestBuilder r) throws Exception {
        return mvc.perform(r.header("Authorization", firm.bearer()));
    }

    private ResultActions adminPost(String path, String body) throws Exception {
        return admin(post(path).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private int createLink(String sourceId, String design, String label) throws Exception {
        String body = """
            {"source_type":"AD","source_id":"%s","design_code":"%s","label":"%s"}""".formatted(sourceId, design, label);
        return json.readTree(adminPost("/api/boutique-marketing/links", body).andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).get("link_id").asInt();
    }

    /** New DRAFT order for KUR-301 (₹1,500 + ₹100 delivery = ₹1,600); extra = additional JSON fields. */
    private ResultActions order(String extra) throws Exception {
        String body = """
            {"customer_id":%d,"design_code":"KUR-301","size_code":"M","option_ids":[],"qty":1,
             "delivery":{"name":"Ad Tester","phone":"+91 98222 33344","address_line1":"1 Ad Rd",
                         "city":"Hyderabad","state":"Telangana","pincode":"500001"},
             "channel":"WHATSAPP","idempotency_key":"mkt-%d"%s}""".formatted(customerId, keys.incrementAndGet(), extra);
        return agentPost("/api/agent/orders", body);
    }

    private String orderNo(ResultActions created) throws Exception {
        return json.readTree(created.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
            .get("order_no").asText();
    }

    private void markPaid(String orderNo) {
        jdbc.update("UPDATE \"" + firm.schema() + "\".boutique_orders SET status = 'PAID', paid_at = NOW() WHERE order_no = ?", orderNo);
    }

    private ResultActions event(String sourceId, String clid, String occurredAt) throws Exception {
        String body = """
            {"source_type":"AD","source_id":"%s","ctwa_clid":%s,"customer_id":%d,"occurred_at":%s}"""
            .formatted(sourceId, clid == null ? "null" : "\"" + clid + "\"", customerId,
                occurredAt == null ? "null" : "\"" + occurredAt + "\"");
        return agentPost("/api/agent/marketing-events", body);
    }

    private static JsonNode rowFor(JsonNode report, String label) {
        for (JsonNode r : report.get("rows")) if (label.equals(r.get("label").asText())) return r;
        throw new AssertionError("No report row " + label + " in " + report);
    }

    // ── tests ────────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    void resolve_activeInactiveUnknown() throws Exception {
        createLink(AD_ACTIVE, "kur-301", "Mustard kurti reel – Diwali boost");
        int inactive = createLink(AD_INACTIVE, "KUR-302", "Rose anarkali story");
        adminPost("/api/boutique-marketing/links/" + inactive + "/deactivate", "").andExpect(status().isOk())
            .andExpect(jsonPath("$.active").value(false));

        agent(get("/api/agent/marketing-links/resolve").param("type", "AD").param("id", AD_ACTIVE))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.design_code").value("KUR-301"))
            .andExpect(jsonPath("$.label").value("Mustard kurti reel – Diwali boost"));
        agent(get("/api/agent/marketing-links/resolve").param("type", "AD").param("id", AD_INACTIVE))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MARKETING_LINK_NOT_FOUND"));
        agent(get("/api/agent/marketing-links/resolve").param("type", "AD").param("id", "999"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MARKETING_LINK_NOT_FOUND"));
        // Same id under the other source type is a different link.
        agent(get("/api/agent/marketing-links/resolve").param("type", "IG_MEDIA").param("id", AD_ACTIVE))
            .andExpect(status().isNotFound());
        agent(get("/api/agent/marketing-links/resolve").param("type", "TV").param("id", AD_ACTIVE))
            .andExpect(status().isBadRequest());
        // Agent API only with the agent's token.
        mvc.perform(get("/api/agent/marketing-links/resolve").param("type", "AD").param("id", AD_ACTIVE)
            .header("Authorization", firm.bearer())).andExpect(status().isUnauthorized());

        // Re-activating makes it resolve again.
        adminPost("/api/boutique-marketing/links/" + inactive + "/activate", "").andExpect(status().isOk());
        agent(get("/api/agent/marketing-links/resolve").param("type", "AD").param("id", AD_INACTIVE))
            .andExpect(status().isOk()).andExpect(jsonPath("$.design_code").value("KUR-302"));
        adminPost("/api/boutique-marketing/links/" + inactive + "/deactivate", "").andExpect(status().isOk());
    }

    @Test
    @Order(2)
    void adminLinks_validationAndAuth() throws Exception {
        adminPost("/api/boutique-marketing/links", """
            {"source_type":"AD","source_id":"12ab","design_code":"KUR-301","label":"x"}""")
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.details.fields.source_id").exists());
        adminPost("/api/boutique-marketing/links", """
            {"source_type":"AD","source_id":"555","design_code":"NOPE-1","label":"x"}""")
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("DESIGN_NOT_FOUND"));
        adminPost("/api/boutique-marketing/links", """
            {"source_type":"AD","source_id":"%s","design_code":"KUR-302","label":"dup"}""".formatted(AD_ACTIVE))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MARKETING_LINK_EXISTS"));

        int id = createLink("777", "KUR-301", "Edit me");
        admin(put("/api/boutique-marketing/links/" + id).contentType(MediaType.APPLICATION_JSON).content("""
            {"source_type":"IG_MEDIA","source_id":"778","design_code":"KUR-302","label":"Edited"}"""))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.source_type").value("IG_MEDIA"))
            .andExpect(jsonPath("$.design_code").value("KUR-302"))
            .andExpect(jsonPath("$.design_name").value("Rose Anarkali"))
            .andExpect(jsonPath("$.active").value(true));

        admin(get("/api/boutique-marketing/links")).andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.source_id == '" + AD_ACTIVE + "')].label").value("Mustard kurti reel – Diwali boost"));
        admin(get("/api/boutique-marketing/designs")).andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.code == 'KUR-301')].name").value("Mustard Kurti"));

        mvc.perform(get("/api/boutique-marketing/links").header("Authorization", AGENT)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/boutique-marketing/links").header("Authorization", superadminBearer())).andExpect(status().isForbidden());
    }

    @Test
    @Order(3)
    void order_storesSourceFields() throws Exception {
        String tagged = orderNo(order(", \"source_type\":\"AD\",\"source_id\":\"" + AD_ACTIVE
            + "\",\"ctwa_clid\":\"ARAkLkA8rmlFeiCktEJQ-QTwRiyYHAFDLMNDBH0CD3qpjd0HR4irJ6LEkR7JwFF4XvnO\""));
        agent(get("/api/agent/orders/" + tagged)).andExpect(status().isOk())
            .andExpect(jsonPath("$.source_type").value("AD"))
            .andExpect(jsonPath("$.source_id").value(AD_ACTIVE))
            .andExpect(jsonPath("$.ctwa_clid").value(org.hamcrest.Matchers.startsWith("ARAkLkA8")));
        assertThat(jdbc.queryForObject("SELECT source_type || ':' || source_id FROM \"" + firm.schema()
            + "\".boutique_orders WHERE order_no = ?", String.class, tagged)).isEqualTo("AD:" + AD_ACTIVE);

        String organic = orderNo(order(""));
        agent(get("/api/agent/orders/" + organic)).andExpect(status().isOk())
            .andExpect(jsonPath("$.source_type").doesNotExist())
            .andExpect(jsonPath("$.ctwa_clid").doesNotExist());

        order(", \"source_type\":\"AD\"").andExpect(status().isBadRequest());
        order(", \"source_type\":\"AD\",\"source_id\":\"abc\"").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.details.fields.source_id").exists());
        order(", \"source_type\":\"TV\",\"source_id\":\"1\"").andExpect(status().isBadRequest());
    }

    @Test
    @Order(4)
    void events_idempotentOnClid() throws Exception {
        event("123", "clid-idem-1", "2026-01-05T10:00:00+05:30").andExpect(status().isCreated())
            .andExpect(jsonPath("$.created").value(true));
        event("123", "clid-idem-1", "2026-01-05T10:00:00+05:30").andExpect(status().isOk())
            .andExpect(jsonPath("$.created").value(false));
        // No click id: not deduplicated.
        event("123", null, "2026-01-05T10:00:00+05:30").andExpect(status().isCreated());
        event("123", null, "2026-01-05T10:00:00+05:30").andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM \"" + firm.schema() + "\".marketing_events WHERE source_id = '123'",
            Long.class)).isEqualTo(3);

        agentPost("/api/agent/marketing-events", "{\"source_type\":\"AD\",\"source_id\":\"x1\"}").andExpect(status().isBadRequest());
        agentPost("/api/agent/marketing-events", "{\"source_type\":\"AD\",\"source_id\":\"1\",\"event_type\":\"clicked\"}")
            .andExpect(status().isBadRequest());
        agentPost("/api/agent/marketing-events", "{\"source_type\":\"AD\",\"source_id\":\"1\",\"customer_id\":99999999}")
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("CUSTOMER_NOT_FOUND"));
    }

    @Test
    @Order(5)
    void report_aggregatesPerLinkAndUnmapped() throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        createLink(AD_REPORT, "KUR-302", "Report ad");

        // 4 conversations for AD_REPORT in range (one reported twice), 1 out of range.
        event(AD_REPORT, "clid-r1", null).andExpect(status().isCreated());
        event(AD_REPORT, "clid-r1", null).andExpect(status().isOk());
        event(AD_REPORT, "clid-r2", null).andExpect(status().isCreated());
        event(AD_REPORT, "clid-r3", null).andExpect(status().isCreated());
        event(AD_REPORT, null, null).andExpect(status().isCreated());
        event(AD_REPORT, "clid-old", today.minusDays(60) + "T12:00:00+05:30").andExpect(status().isCreated());
        // 2 conversations for an ad nobody mapped.
        event(AD_UNMAPPED, "clid-u1", null).andExpect(status().isCreated());
        event(AD_UNMAPPED, "clid-u2", null).andExpect(status().isCreated());

        // AD_REPORT: 3 orders, 2 paid (₹1,600 each). Unmapped ad: 1 paid order.
        String src = ", \"source_type\":\"AD\",\"source_id\":\"%s\"";
        String r1 = orderNo(order(src.formatted(AD_REPORT)));
        String r2 = orderNo(order(src.formatted(AD_REPORT)));
        orderNo(order(src.formatted(AD_REPORT)));
        markPaid(r1);
        markPaid(r2);
        markPaid(orderNo(order(src.formatted(AD_UNMAPPED))));

        JsonNode report = json.readTree(admin(get("/api/boutique-marketing/report")
                .param("from", today.minusDays(6).toString()).param("to", today.toString()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));

        JsonNode ad = rowFor(report, "Report ad");
        assertThat(ad.get("source_id").asText()).isEqualTo(AD_REPORT);
        assertThat(ad.get("design_code").asText()).isEqualTo("KUR-302");
        assertThat(ad.get("conversations").asLong()).isEqualTo(4);
        assertThat(ad.get("orders").asLong()).isEqualTo(3);
        assertThat(ad.get("paid_orders").asLong()).isEqualTo(2);
        assertThat(ad.get("paid_revenue").decimalValue()).isEqualByComparingTo("3200.00");
        assertThat(ad.get("conversion_pct").decimalValue()).isEqualByComparingTo("50.0");

        // Unmapped/other = the unmapped ad (2 conversations, 1 paid order) + the organic and earlier tagged orders
        // from order_storesSourceFields (AD_ACTIVE has a link, so that one is counted on its own row).
        JsonNode other = rowFor(report, "Unmapped/other");
        assertThat(other.get("link_id").isNull()).isTrue();
        assertThat(other.get("conversations").asLong()).isEqualTo(2);
        assertThat(other.get("paid_orders").asLong()).isEqualTo(1);
        assertThat(other.get("paid_revenue").decimalValue()).isEqualByComparingTo("1600.00");

        JsonNode mustard = rowFor(report, "Mustard kurti reel – Diwali boost");
        assertThat(mustard.get("orders").asLong()).isEqualTo(1);
        assertThat(mustard.get("conversations").asLong()).isZero();
        assertThat(mustard.get("conversion_pct").isNull()).isTrue();
        // Inactive links stay in the report.
        assertThat(rowFor(report, "Rose anarkali story").get("active").asBoolean()).isFalse();

        long totalOrders = 0;
        for (JsonNode r : report.get("rows")) totalOrders += r.get("orders").asLong();
        assertThat(report.get("total").get("orders").asLong()).isEqualTo(totalOrders);
        assertThat(report.get("total").get("paid_revenue").decimalValue()).isEqualByComparingTo("4800.00");

        // The old conversation is only inside a wider range.
        JsonNode wide = json.readTree(admin(get("/api/boutique-marketing/report")
                .param("from", today.minusDays(90).toString()).param("to", today.toString()))
            .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(rowFor(wide, "Report ad").get("conversations").asLong()).isEqualTo(5);

        admin(get("/api/boutique-marketing/report").param("from", today.toString()).param("to", today.minusDays(1).toString()))
            .andExpect(status().isBadRequest());
    }
}

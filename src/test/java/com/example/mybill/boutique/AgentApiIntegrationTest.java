package com.example.mybill.boutique;

import com.example.mybill.boutique.config.AgentTokenVerifier;
import com.example.mybill.boutique.config.AgentTokenVerifier.VerifiedToken;
import com.example.mybill.boutique.config.BoutiqueSchemaInitializer;
import com.example.mybill.wholesale.AbstractFirmIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end /api/agent/** tests against a real PostgreSQL (Testcontainers; skipped without Docker).
 * Google token verification is mocked: "Bearer agent-token" = the allowed service account,
 * "Bearer other-token" = a different one, anything else = invalid.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = {
    "agent.auth.enabled=true",
    "agent.api.audience=https://mybill-service.test",
    "agent.allowed-sa-email=srisa-agent-sa@test.iam.gserviceaccount.com",
    "agent.tenant-schema=firm_agentapi",
    "boutique.delivery.flat-charge=100",
    "boutique.delivery.free-above=2500"
})
class AgentApiIntegrationTest extends AbstractFirmIntegrationTest {

    private static final String AGENT = "Bearer agent-token";

    @MockBean private AgentTokenVerifier tokenVerifier;
    @Autowired private BoutiqueSchemaInitializer boutiqueSchemaInitializer;

    private final ObjectMapper json = new ObjectMapper();
    private FirmCtx firm;
    private int kur101;
    private int sleeveHalf, sleeveFull, neckSweetheart, neckOtherDesign, inactiveOption;

    @BeforeAll
    void setUp() {
        firm = registerFirm("agentapi");
        boutiqueSchemaInitializer.ensureSchema(firm.schema());
        String s = "\"" + firm.schema() + "\".";

        kur101 = insertDesign(s, "KUR-101", "Anarkali Kurta", "1299.00", true, "NOW() - INTERVAL '2 days'");
        int kur102 = insertDesign(s, "KUR-102", "Straight Kurta", "999.00", true, "NOW() - INTERVAL '1 day'");
        insertDesign(s, "KUR-103", "A-line Kurta", "2499.00", true, "NOW()");
        insertDesign(s, "KUR-900", "Retired Kurta", "500.00", false, "NOW() + INTERVAL '1 day'");

        jdbc.update("INSERT INTO " + s + "boutique_design_images (design_id, image_url, sort_order) VALUES (?, ?, 1), (?, ?, 0)",
            kur101, "https://img.test/kur-101-back.jpg", kur101, "https://img.test/kur-101-front.jpg");

        sleeveHalf = insertOption(s, null, "SLEEVE", "Half sleeve", "0", true);
        sleeveFull = insertOption(s, null, "SLEEVE", "Full sleeve", "150", true);
        neckSweetheart = insertOption(s, kur101, "NECK", "Sweetheart neck", "200", true);
        neckOtherDesign = insertOption(s, kur102, "NECK", "Mandarin collar", "150", true);
        inactiveOption = insertOption(s, null, "LINING", "Silk lining", "500", false);
    }

    @BeforeEach
    void tokens() {
        when(tokenVerifier.verify(anyString(), anyString())).thenReturn(Optional.empty());
        when(tokenVerifier.verify(eq("agent-token"), eq("https://mybill-service.test"))).thenReturn(
            Optional.of(new VerifiedToken("https://accounts.google.com", "srisa-agent-sa@test.iam.gserviceaccount.com", true)));
        when(tokenVerifier.verify(eq("other-token"), eq("https://mybill-service.test"))).thenReturn(
            Optional.of(new VerifiedToken("https://accounts.google.com", "someone-else@test.iam.gserviceaccount.com", true)));
    }

    private int insertDesign(String s, String code, String name, String price, boolean active, String createdAtSql) {
        return jdbc.queryForObject("INSERT INTO " + s + "boutique_designs (code, name, garment_type, description, rough_price, "
            + "delivery_days, is_active, created_at) VALUES (?, ?, 'Kurta', 'Test design', ?::numeric, 7, ?, " + createdAtSql
            + ") RETURNING design_id", Integer.class, code, name, price, active);
    }

    private int insertOption(String s, Integer designId, String group, String name, String delta, boolean active) {
        return jdbc.queryForObject("INSERT INTO " + s + "customisation_options (design_id, option_group, name, price_delta, is_active) "
            + "VALUES (?, ?, ?, ?::numeric, ?) RETURNING option_id", Integer.class, designId, group, name, delta, active);
    }

    private ResultActions agent(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", AGENT));
    }

    private ResultActions agentPost(String path, String body) throws Exception {
        return agent(post(path).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private int newCustomer(String phone) throws Exception {
        String body = agentPost("/api/agent/customers/upsert", "{\"phone_e164\":\"" + phone + "\",\"name\":\"Test\"}")
            .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("customer_id").asInt();
    }

    // ── Security ─────────────────────────────────────────────────────────────

    @Test
    void withoutToken_is401_andAppJwtIsNotAccepted() throws Exception {
        mvc.perform(get("/api/agent/size-chart")).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(get("/api/agent/size-chart").header("Authorization", firm.bearer()))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void otherServiceAccount_is403() throws Exception {
        mvc.perform(get("/api/agent/size-chart").header("Authorization", "Bearer other-token"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void existingUiAuthIsUnaffected() throws Exception {
        mvc.perform(get("/api/boutique-designs").header("Authorization", firm.bearer())).andExpect(status().isOk());
        mvc.perform(get("/api/boutique-designs")).andExpect(status().isUnauthorized());
    }

    // ── Catalog ──────────────────────────────────────────────────────────────

    @Test
    void design_withImagesAndGroupedOptions() throws Exception {
        agent(get("/api/agent/designs/kur-101"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("KUR-101"))
            .andExpect(jsonPath("$.base_price").value(1299.00))
            .andExpect(jsonPath("$.lead_time_days").value(7))
            .andExpect(jsonPath("$.images[0].url").value("https://img.test/kur-101-front.jpg"))
            .andExpect(jsonPath("$.option_groups.length()").value(2))
            .andExpect(jsonPath("$.option_groups[0].group").value("NECK"))
            .andExpect(jsonPath("$.option_groups[0].options.length()").value(1))   // not KUR-102's collar
            .andExpect(jsonPath("$.option_groups[1].group").value("SLEEVE"))
            .andExpect(jsonPath("$.option_groups[1].options[1].price_delta").value(150.00));
    }

    @Test
    void inactiveOrUnknownDesign_is404() throws Exception {
        agent(get("/api/agent/designs/KUR-900")).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("DESIGN_NOT_FOUND"));
        agent(get("/api/agent/designs/NOPE-1")).andExpect(status().isNotFound());
    }

    @Test
    void latestDesigns_newestActiveFirst() throws Exception {
        agent(get("/api/agent/designs/latest?limit=2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].code").value("KUR-103"))
            .andExpect(jsonPath("$[1].code").value("KUR-102"));
        agent(get("/api/agent/designs/latest?limit=3"))
            .andExpect(jsonPath("$[2].image_url").value("https://img.test/kur-101-front.jpg"));
    }

    @Test
    void sizeChart_isSeeded() throws Exception {
        agent(get("/api/agent/size-chart"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(6))
            .andExpect(jsonPath("$[0].size_code").value("XS"))
            .andExpect(jsonPath("$[2].bust_in").value(36.0));
    }

    // ── Customers & measurements ─────────────────────────────────────────────

    @Test
    void upsert_createsThenFindsAndMergesMissingFields() throws Exception {
        String created = agentPost("/api/agent/customers/upsert", "{\"phone_e164\":\"+919811100001\"}")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.source").value("WHATSAPP"))
            .andReturn().getResponse().getContentAsString();
        int id = json.readTree(created).get("customer_id").asInt();

        agentPost("/api/agent/customers/upsert",
                "{\"phone_e164\":\"+919811100001\",\"instagram_user_id\":\"ig-777\",\"name\":\"Asha\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.customer_id").value(id))
            .andExpect(jsonPath("$.instagram_user_id").value("ig-777"))
            .andExpect(jsonPath("$.name").value("Asha"));

        agentPost("/api/agent/customers/upsert", "{\"instagram_user_id\":\"ig-777\",\"name\":\"Someone else\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.customer_id").value(id))
            .andExpect(jsonPath("$.name").value("Asha"));    // existing name is not overwritten
    }

    @Test
    void upsert_linksExistingShopCustomerByLegacyPhone() throws Exception {
        int shopCustomer = jdbc.queryForObject("INSERT INTO \"" + firm.schema() + "\".customers (customer_name, phone, created_at) "
            + "VALUES ('Shop Lady', '98480 12345', NOW()) RETURNING customer_id", Integer.class);
        agentPost("/api/agent/customers/upsert", "{\"phone_e164\":\"+919848012345\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.customer_id").value(shopCustomer))
            .andExpect(jsonPath("$.source").value("SHOP"))
            .andExpect(jsonPath("$.phone_e164").value("+919848012345"));
    }

    @Test
    void upsert_requiresAnIdentifier() throws Exception {
        agentPost("/api/agent/customers/upsert", "{\"name\":\"Nobody\"}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        agentPost("/api/agent/customers/upsert", "{\"phone_e164\":\"9876543210\"}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.details.fields.phone_e164").exists());
    }

    @Test
    void measurements_historyAndLatest() throws Exception {
        int id = newCustomer("+919811100002");
        agent(get("/api/agent/customers/" + id + "/measurements")).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("MEASUREMENTS_NOT_FOUND"));

        agentPost("/api/agent/customers/" + id + "/measurements", "{\"size_code\":\"XXXL\"}")
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("INVALID_SIZE"));
        agentPost("/api/agent/customers/" + id + "/measurements", "{\"size_code\":\"M\",\"bust_in\":92}")
            .andExpect(status().isBadRequest());   // centimetres entered as inches

        agentPost("/api/agent/customers/" + id + "/measurements",
                "{\"size_code\":\"m\",\"height_cm\":160,\"bust_in\":36,\"waist_in\":30,\"hip_in\":39,\"fit_preference\":\"regular\"}")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.size_code").value("M"))
            .andExpect(jsonPath("$.fit_preference").value("REGULAR"));
        agentPost("/api/agent/customers/" + id + "/measurements", "{\"size_code\":\"L\",\"bust_in\":38}")
            .andExpect(status().isCreated());

        agent(get("/api/agent/customers/" + id + "/measurements"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.size_code").value("L"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM \"" + firm.schema() + "\".customer_measurements WHERE customer_id = ?",
            Integer.class, id)).isEqualTo(2);
    }

    @Test
    void measurements_unknownCustomer_is404() throws Exception {
        agent(get("/api/agent/customers/999999/measurements")).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("CUSTOMER_NOT_FOUND"));
    }

    // ── Quotes & orders ──────────────────────────────────────────────────────

    @Test
    void quote_pricesOptionsAndDelivery() throws Exception {
        agentPost("/api/agent/orders/quote", """
                {"design_code":"KUR-101","size_code":"M","option_ids":[%d,%d],"pincode":"500001"}
                """.formatted(sleeveFull, neckSweetheart))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.breakdown.base_price").value(1299.00))
            .andExpect(jsonPath("$.breakdown.options_total").value(350.00))
            .andExpect(jsonPath("$.breakdown.unit_price").value(1649.00))
            .andExpect(jsonPath("$.breakdown.delivery_charge").value(100.00))
            .andExpect(jsonPath("$.breakdown.total").value(1749.00))
            .andExpect(jsonPath("$.breakdown.currency").value("INR"));
    }

    @Test
    void quote_rejectsInvalidSelections() throws Exception {
        agentPost("/api/agent/orders/quote", """
                {"design_code":"KUR-101","size_code":"M","option_ids":[%d,%d]}""".formatted(sleeveHalf, sleeveFull))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("OPTION_GROUP_CONFLICT"));
        agentPost("/api/agent/orders/quote", """
                {"design_code":"KUR-101","size_code":"M","option_ids":[%d]}""".formatted(neckOtherDesign))
            .andExpect(jsonPath("$.code").value("INVALID_OPTION"));
        agentPost("/api/agent/orders/quote", """
                {"design_code":"KUR-101","size_code":"M","option_ids":[%d]}""".formatted(inactiveOption))
            .andExpect(jsonPath("$.code").value("INVALID_OPTION"));
        agentPost("/api/agent/orders/quote", "{\"design_code\":\"KUR-900\",\"size_code\":\"M\"}")
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("DESIGN_INACTIVE"));
    }

    private String orderBody(int customerId, String key) {
        return """
            {"customer_id":%d,"design_code":"KUR-101","size_code":"M","option_ids":[%d],"qty":2,
             "delivery":{"name":"Asha Rao","phone":"+91 98765 43210","address_line1":"12 MG Road",
                         "city":"Hyderabad","state":"Telangana","pincode":"500001"},
             "channel":"whatsapp","idempotency_key":"%s"}""".formatted(customerId, sleeveFull, key);
    }

    @Test
    void createOrder_isIdempotentAndSnapshotsPrices() throws Exception {
        int customerId = newCustomer("+919811100003");
        agentPost("/api/agent/customers/" + customerId + "/measurements", "{\"size_code\":\"M\",\"bust_in\":36}");

        String first = agentPost("/api/agent/orders", orderBody(customerId, "conv-1:order-1"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.order_no").value(startsWith("SB-")))
            .andExpect(jsonPath("$.status").value("DRAFT"))
            .andExpect(jsonPath("$.channel").value("WHATSAPP"))
            .andExpect(jsonPath("$.items[0].unit_price").value(1449.00))
            .andExpect(jsonPath("$.items[0].qty").value(2))
            .andExpect(jsonPath("$.items[0].options[0].name").value("Full sleeve"))
            .andExpect(jsonPath("$.items[0].measurements.bust_in").value(36.0))
            .andExpect(jsonPath("$.subtotal").value(2898.00))
            .andExpect(jsonPath("$.delivery_charge").value(0.00))     // above the free-delivery threshold
            .andExpect(jsonPath("$.total").value(2898.00))
            .andReturn().getResponse().getContentAsString();
        String orderNo = json.readTree(first).get("order_no").asText();

        // Retry with the same key: same order, no duplicate row.
        agentPost("/api/agent/orders", orderBody(customerId, "conv-1:order-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.order_no").value(orderNo));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM \"" + firm.schema() + "\".boutique_orders WHERE idempotency_key = ?",
            Integer.class, "conv-1:order-1")).isEqualTo(1);

        // A later price change does not alter the stored order.
        jdbc.update("UPDATE \"" + firm.schema() + "\".customisation_options SET price_delta = 999 WHERE option_id = ?", sleeveFull);
        try {
            JsonNode order = json.readTree(agent(get("/api/agent/orders/" + orderNo.toLowerCase()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertThat(order.get("total").decimalValue()).isEqualByComparingTo("2898.00");
            assertThat(order.get("items").get(0).get("options").get(0).get("price_delta").decimalValue())
                .isEqualByComparingTo("150.00");
        } finally {
            jdbc.update("UPDATE \"" + firm.schema() + "\".customisation_options SET price_delta = 150 WHERE option_id = ?", sleeveFull);
        }
    }

    @Test
    void createOrder_sameKeyForAnotherCustomer_is409() throws Exception {
        int a = newCustomer("+919811100004");
        int b = newCustomer("+919811100005");
        agentPost("/api/agent/orders", orderBody(a, "shared-key")).andExpect(status().isCreated());
        agentPost("/api/agent/orders", orderBody(b, "shared-key"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void createOrder_validation() throws Exception {
        agentPost("/api/agent/orders", orderBody(999999, "missing-customer"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("CUSTOMER_NOT_FOUND"));
        agentPost("/api/agent/orders", "{\"design_code\":\"KUR-101\"}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.details.fields.idempotency_key").exists());
    }

    @Test
    void unknownOrder_is404() throws Exception {
        agent(get("/api/agent/orders/SB-1")).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }
}

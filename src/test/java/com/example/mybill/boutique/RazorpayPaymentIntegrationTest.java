package com.example.mybill.boutique;

import com.example.mybill.boutique.config.AgentTokenVerifier;
import com.example.mybill.boutique.config.AgentTokenVerifier.VerifiedToken;
import com.example.mybill.boutique.config.BoutiqueSchemaInitializer;
import com.example.mybill.boutique.payment.RazorpaySignature;
import com.example.mybill.wholesale.AbstractFirmIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
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
 * Payment Links end to end: real PostgreSQL (Testcontainers) + a fake Razorpay HTTP API (JDK HttpServer) that also
 * receives the srisa-agent notifications. Skipped without Docker.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = {
    "agent.auth.enabled=true",
    "agent.api.audience=https://mybill-service.test",
    "agent.allowed-sa-email=srisa-agent-sa@test.iam.gserviceaccount.com",
    "agent.tenant-schema=firm_payapi",
    "razorpay.key_id=rzp_test_key",
    "razorpay.key_secret=rzp_test_secret",
    "razorpay.webhook-secret=whsec_test",
    "boutique.payment.link-expiry-hours=24",
    "boutique.payment.callback-url=https://srisafabrics.com/payment-thanks",
    "agent.events.auth-enabled=false",
    "agent.events.backoff-ms=10",
    "reconcile.auth-enabled=false",
    "boutique.delivery.flat-charge=100",
    "boutique.delivery.free-above=2500"
})
class RazorpayPaymentIntegrationTest extends AbstractFirmIntegrationTest {

    private static final String AGENT = "Bearer agent-token";
    private static final FakeRazorpay RAZORPAY = new FakeRazorpay();

    @DynamicPropertySource
    static void fakeServers(DynamicPropertyRegistry registry) {
        registry.add("razorpay.api.base-url", RAZORPAY::baseUrl);
        registry.add("agent.events.url", RAZORPAY::baseUrl);
    }

    @MockBean private AgentTokenVerifier tokenVerifier;
    @Autowired private BoutiqueSchemaInitializer boutiqueSchemaInitializer;

    private final ObjectMapper json = new ObjectMapper();
    private FirmCtx firm;
    private int customerId;
    private final AtomicInteger keys = new AtomicInteger();

    @BeforeAll
    void setUp() throws Exception {
        firm = registerFirm("payapi");
        boutiqueSchemaInitializer.ensureSchema(firm.schema());
        jdbc.update("INSERT INTO \"" + firm.schema() + "\".boutique_designs (code, name, garment_type, rough_price, delivery_days, "
            + "is_active, created_at) VALUES ('KUR-201', 'Test Kurta', 'Kurta', 1500.00, 7, TRUE, NOW())");
        tokens();
        customerId = json.readTree(agentPost("/api/agent/customers/upsert", "{\"phone_e164\":\"+919811122233\",\"name\":\"Pay Tester\"}")
            .andReturn().getResponse().getContentAsString()).get("customer_id").asInt();
    }

    @AfterAll
    void tearDown() { RAZORPAY.stop(); }

    @BeforeEach
    void tokens() {
        when(tokenVerifier.verify(anyString(), anyString())).thenReturn(Optional.empty());
        when(tokenVerifier.verify(eq("agent-token"), eq("https://mybill-service.test"))).thenReturn(
            Optional.of(new VerifiedToken("https://accounts.google.com", "srisa-agent-sa@test.iam.gserviceaccount.com", true)));
        RAZORPAY.reset();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private ResultActions agent(MockHttpServletRequestBuilder r) throws Exception {
        return mvc.perform(r.header("Authorization", AGENT));
    }

    private ResultActions agentPost(String path, String body) throws Exception {
        return agent(post(path).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** New DRAFT order (KUR-201, ₹1,500 + ₹100 delivery = ₹1,600) -> order number. */
    private String newOrder() throws Exception {
        String body = """
            {"customer_id":%d,"design_code":"KUR-201","size_code":"M","option_ids":[],"qty":1,
             "measurements":{"bust_in":36,"waist_in":30,"hip_in":39,"fit_preference":"REGULAR"},
             "delivery":{"name":"Pay Tester","phone":"+91 98111 22233","address_line1":"1 Test Rd",
                         "city":"Hyderabad","state":"Telangana","pincode":"500001"},
             "channel":"WHATSAPP","idempotency_key":"pay-%d"}""".formatted(customerId, keys.incrementAndGet());
        return json.readTree(agentPost("/api/agent/orders", body).andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString()).get("order_no").asText();
    }

    private JsonNode link(String orderNo) throws Exception {
        return json.readTree(agentPost("/api/agent/orders/" + orderNo + "/payment-link", "")
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private String linkIdFor(String orderNo) {
        return jdbc.queryForObject("SELECT p.razorpay_payment_link_id FROM \"" + firm.schema() + "\".boutique_order_payments p "
            + "JOIN \"" + firm.schema() + "\".boutique_orders o ON o.order_id = p.order_id WHERE o.order_no = ? "
            + "ORDER BY p.created_at DESC, p.payment_id DESC LIMIT 1", String.class, orderNo);
    }

    private String webhookBody(String event, String linkId, long amountPaid, String currency) {
        return """
            {"entity":"event","event":"%s","contains":["payment_link","payment"],"payload":{
              "payment_link":{"entity":{"id":"%s","status":"%s","amount":160000,"amount_paid":%d,"currency":"%s",
                                        "notes":{"tenant":"firm_payapi"}}},
              "payment":{"entity":{"id":"pay_%s","amount":%d,"currency":"%s","status":"captured","created_at":1760700000}}}}"""
            .formatted(event, linkId, event.substring("payment_link.".length()), amountPaid, currency, linkId, amountPaid, currency);
    }

    private ResultActions webhook(String body, String eventId) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return mvc.perform(post("/webhooks/razorpay").contentType(MediaType.APPLICATION_JSON).content(bytes)
            .header("X-Razorpay-Signature", RazorpaySignature.sign(bytes, "whsec_test"))
            .header("x-razorpay-event-id", eventId));
    }

    private String orderStatus(String orderNo) {
        return jdbc.queryForObject("SELECT status FROM \"" + firm.schema() + "\".boutique_orders WHERE order_no = ?", String.class, orderNo);
    }

    private Map<String, Object> payment(String linkId) {
        return jdbc.queryForMap("SELECT * FROM \"" + firm.schema() + "\".boutique_order_payments WHERE razorpay_payment_link_id = ?", linkId);
    }

    private int stitchingOrdersFor(String orderNo) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM \"" + firm.schema() + "\".stitching_orders WHERE notes LIKE ?",
            Integer.class, "%" + orderNo + "%");
    }

    // ── link creation ────────────────────────────────────────────────────────

    @Test
    void createLink_isIdempotent_andUsesDbAmount() throws Exception {
        String orderNo = newOrder();
        JsonNode first = link(orderNo);
        JsonNode second = link(orderNo);
        assertThat(second.get("short_url").asText()).isEqualTo(first.get("short_url").asText());
        assertThat(RAZORPAY.created).hasSize(1);
        assertThat(first.get("amount").decimalValue()).isEqualByComparingTo("1600.00");
        assertThat(first.get("status").asText()).isEqualTo("created");
        assertThat(first.get("order_status").asText()).isEqualTo("PAYMENT_PENDING");

        JsonNode body = RAZORPAY.created.get(0);
        assertThat(body.get("amount").asLong()).isEqualTo(160000);
        assertThat(body.get("currency").asText()).isEqualTo("INR");
        assertThat(body.get("reference_id").asText()).isEqualTo(orderNo);
        assertThat(body.get("customer").get("contact").asText()).isEqualTo("919811122233");
        assertThat(body.get("notify").get("sms").asBoolean()).isFalse();
        assertThat(body.get("reminder_enable").asBoolean()).isFalse();
        assertThat(body.get("callback_method").asText()).isEqualTo("get");
        assertThat(body.get("notes").get("tenant").asText()).isEqualTo("firm_payapi");
        assertThat(body.get("expire_by").asLong()).isGreaterThan(System.currentTimeMillis() / 1000 + 15 * 60);
        assertThat(RAZORPAY.lastAuthorization).isEqualTo("Basic " + Base64.getEncoder()
            .encodeToString("rzp_test_key:rzp_test_secret".getBytes(StandardCharsets.UTF_8)));
        assertThat(orderStatus(orderNo)).isEqualTo("PAYMENT_PENDING");
    }

    @Test
    void expiredLink_getsReplacementWithSuffixedReference() throws Exception {
        String orderNo = newOrder();
        link(orderNo);
        webhook(webhookBody("payment_link.expired", linkIdFor(orderNo), 0, "INR"), "evt_exp_" + orderNo).andExpect(status().isOk());
        assertThat(orderStatus(orderNo)).isEqualTo("PAYMENT_PENDING");
        JsonNode replacement = link(orderNo);
        assertThat(RAZORPAY.created).hasSize(2);
        assertThat(RAZORPAY.created.get(1).get("reference_id").asText()).isEqualTo(orderNo + "-2");
        assertThat(replacement.get("status").asText()).isEqualTo("created");
        RAZORPAY.awaitEvents(1);
        assertThat(RAZORPAY.events.get(0).get("event").asText()).isEqualTo("payment_link.expired");
    }

    // ── webhooks ─────────────────────────────────────────────────────────────

    @Test
    void signatureIsRequired() throws Exception {
        byte[] body = webhookBody("payment_link.paid", "plink_x", 160000, "INR").getBytes(StandardCharsets.UTF_8);
        mvc.perform(post("/webhooks/razorpay").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/webhooks/razorpay").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-Razorpay-Signature", RazorpaySignature.sign(body, "wrong_secret")))
            .andExpect(status().isBadRequest());
    }

    @Test
    void paid_marksPaid_createsStitchingOrder_notifiesAgent_once() throws Exception {
        String orderNo = newOrder();
        link(orderNo);
        String linkId = linkIdFor(orderNo);
        String body = webhookBody("payment_link.paid", linkId, 160000, "INR");
        webhook(body, "evt_paid_" + orderNo).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("paid"));
        webhook(body, "evt_paid_" + orderNo).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("duplicate"));

        assertThat(orderStatus(orderNo)).isEqualTo("PAID");
        Map<String, Object> p = payment(linkId);
        assertThat(p.get("status")).isEqualTo("paid");
        assertThat(p.get("razorpay_payment_id")).isEqualTo("pay_" + linkId);
        assertThat(p.get("paid_at")).isNotNull();
        assertThat(stitchingOrdersFor(orderNo)).isEqualTo(1);

        RAZORPAY.awaitEvents(1);
        JsonNode event = RAZORPAY.events.get(0);
        assertThat(event.get("event").asText()).isEqualTo("order.paid");
        assertThat(event.get("order_no").asText()).isEqualTo(orderNo);
        assertThat(event.get("customer_phone").asText()).isEqualTo("+919811122233");
        assertThat(event.get("event_id").asText()).isEqualTo("evt_paid_" + orderNo);
        Thread.sleep(300);
        assertThat(RAZORPAY.events).hasSize(1);

        // A different event id for the same payment (Razorpay retry with new id) is still a no-op.
        webhook(body, "evt_paid_again_" + orderNo).andExpect(jsonPath("$.status").value("already_paid"));
        assertThat(stitchingOrdersFor(orderNo)).isEqualTo(1);

        agent(get("/api/agent/orders/" + orderNo + "/payment"))
            .andExpect(jsonPath("$.status").value("paid"))
            .andExpect(jsonPath("$.order_status").value("PAID"));
        agentPost("/api/agent/orders/" + orderNo + "/payment-link", "")
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_NOT_PAYABLE"));
        mvc.perform(get("/api/boutique-orders?status=PAID").header("Authorization", firm.bearer()))
            .andExpect(jsonPath("$[?(@.order_no == '" + orderNo + "')].payment_status").value("paid"));
    }

    @Test
    void amountMismatch_isNotMarkedPaid() throws Exception {
        String orderNo = newOrder();
        link(orderNo);
        String linkId = linkIdFor(orderNo);
        webhook(webhookBody("payment_link.paid", linkId, 100, "INR"), "evt_short_" + orderNo)
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("needs_review"));
        Map<String, Object> p = payment(linkId);
        assertThat(p.get("status")).isEqualTo("created");
        assertThat(p.get("needs_review")).isEqualTo(true);
        assertThat(String.valueOf(p.get("review_reason"))).contains("expected 160000");
        assertThat(orderStatus(orderNo)).isEqualTo("PAYMENT_PENDING");
        assertThat(stitchingOrdersFor(orderNo)).isZero();
    }

    @Test
    void paidThenExpired_outOfOrder_staysPaid() throws Exception {
        String orderNo = newOrder();
        link(orderNo);
        String linkId = linkIdFor(orderNo);
        webhook(webhookBody("payment_link.paid", linkId, 160000, "INR"), "evt_p_" + orderNo).andExpect(status().isOk());
        webhook(webhookBody("payment_link.expired", linkId, 0, "INR"), "evt_e_" + orderNo)
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ignored"));
        assertThat(payment(linkId).get("status")).isEqualTo("paid");
        assertThat(orderStatus(orderNo)).isEqualTo("PAID");
    }

    // ── reconciliation ───────────────────────────────────────────────────────

    @Test
    void reconciliation_marksPaidWhenWebhookWasMissed() throws Exception {
        String orderNo = newOrder();
        link(orderNo);
        String linkId = linkIdFor(orderNo);
        jdbc.update("UPDATE \"" + firm.schema() + "\".boutique_order_payments SET created_at = created_at - INTERVAL '10 minutes' "
            + "WHERE razorpay_payment_link_id = ?", linkId);
        RAZORPAY.remoteStatus.put(linkId, "paid");

        mvc.perform(post("/internal/jobs/reconcile-payments"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.marked_paid").value(1));
        assertThat(payment(linkId).get("status")).isEqualTo("paid");
        assertThat(orderStatus(orderNo)).isEqualTo("PAID");
        assertThat(stitchingOrdersFor(orderNo)).isEqualTo(1);

        mvc.perform(post("/internal/jobs/reconcile-payments")).andExpect(jsonPath("$.marked_paid").value(0));
    }

    // ── fake Razorpay (+ srisa-agent /internal/events) ───────────────────────

    static final class FakeRazorpay {
        final List<JsonNode> created = new CopyOnWriteArrayList<>();
        final List<JsonNode> events = new CopyOnWriteArrayList<>();
        final Map<String, String> remoteStatus = new java.util.concurrent.ConcurrentHashMap<>();
        final Map<String, JsonNode> links = new java.util.concurrent.ConcurrentHashMap<>();
        volatile String lastAuthorization;
        private final AtomicInteger seq = new AtomicInteger();
        private final ObjectMapper om = new ObjectMapper();
        private final HttpServer server;

        FakeRazorpay() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            server.createContext("/v1/payment_links", exchange -> {
                lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
                String response;
                if ("POST".equals(exchange.getRequestMethod())) {
                    JsonNode body = om.readTree(exchange.getRequestBody());
                    created.add(body);
                    String id = "plink_" + seq.incrementAndGet() + "_" + System.nanoTime();
                    var node = om.createObjectNode().put("id", id).put("short_url", "https://rzp.io/i/" + id)
                        .put("status", "created").put("amount", body.get("amount").asLong())
                        .put("expire_by", body.get("expire_by").asLong()).put("reference_id", body.get("reference_id").asText());
                    links.put(id, body);
                    response = node.toString();
                } else {
                    String id = exchange.getRequestURI().getPath().substring("/v1/payment_links/".length());
                    JsonNode body = links.get(id);
                    String st = remoteStatus.getOrDefault(id, "created");
                    long amount = body == null ? 0 : body.get("amount").asLong();
                    response = om.createObjectNode().put("id", id).put("status", st).put("amount", amount)
                        .put("amount_paid", "paid".equals(st) ? amount : 0).put("currency", "INR")
                        .set("payments", "paid".equals(st) ? om.readTree("[{\"payment_id\":\"pay_rec_" + id
                            + "\",\"status\":\"captured\",\"created_at\":1760700000}]") : om.createArrayNode()).toString();
                }
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.createContext("/internal/events", exchange -> {
                events.add(om.readTree(exchange.getRequestBody()));
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });
            server.start();
        }

        String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

        void reset() { created.clear(); events.clear(); remoteStatus.clear(); }

        void awaitEvents(int n) throws InterruptedException {
            for (int i = 0; i < 100 && events.size() < n; i++) Thread.sleep(50);
            assertThat(events).hasSizeGreaterThanOrEqualTo(n);
        }

        void stop() { server.stop(0); }
    }
}

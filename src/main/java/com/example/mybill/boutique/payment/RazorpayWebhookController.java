package com.example.mybill.boutique.payment;

import com.example.mybill.boutique.config.BoutiqueSchemaInitializer;
import com.example.mybill.repository.FirmRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Razorpay webhooks (public URL; TenantFilter skips /webhooks/). Configure in the Razorpay dashboard:
 * URL https://<mybill-service>/webhooks/razorpay, events payment_link.paid / .expired / .cancelled, secret =
 * RAZORPAY_WEBHOOK_SECRET.
 *
 * Signature first (raw bytes, before JSON parsing) -> dedupe by x-razorpay-event-id -> idempotent handler.
 * A processing failure answers 500 so Razorpay retries; the event row stays unprocessed and is re-run.
 */
@RestController
public class RazorpayWebhookController {

    private static final Logger log = LoggerFactory.getLogger(RazorpayWebhookController.class);

    @Value("${razorpay.webhook-secret:}") private String webhookSecret;
    @Value("${agent.tenant-schema:firm_srisa}") private String defaultTenant;

    @Autowired private PaymentLinkService paymentLinkService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private FirmRepository firmRepository;
    @Autowired private BoutiqueSchemaInitializer schemaInitializer;
    @Autowired private TenantWork tenantWork;
    @Autowired private com.example.mybill.service.EcomPaymentService ecomPaymentService;

    @PostMapping("/webhooks/razorpay")
    public ResponseEntity<Map<String, Object>> receive(@RequestBody(required = false) byte[] body,
                                                       @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature,
                                                       @RequestHeader(value = "x-razorpay-event-id", required = false) String eventIdHeader) {
        byte[] raw = body == null ? new byte[0] : body;
        if (webhookSecret.isBlank()) {
            log.error("RAZORPAY_WEBHOOK_SECRET is not configured; rejecting webhook");
            return ResponseEntity.status(503).body(Map.of("error", "not_configured"));
        }
        if (!RazorpaySignature.isValid(raw, signature, webhookSecret)) {
            log.warn("Rejected Razorpay webhook: missing or invalid X-Razorpay-Signature");
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_signature"));
        }

        JsonNode payload;
        try {
            payload = objectMapper.readTree(raw);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_json"));
        }
        String eventType = payload.path("event").asText("");
        JsonNode link = payload.path("payload").path("payment_link").path("entity");
        String eventId = eventIdHeader != null && !eventIdHeader.isBlank() ? eventIdHeader
            : "sha256:" + HexFormat.of().formatHex(sha256(raw));
        JsonNode rzpOrder = payload.path("payload").path("order").path("entity");
        String tenant = resolveTenant(eventType.equals("order.paid")
            ? rzpOrder.path("notes").path("tenant").asText(null) : link.path("notes").path("tenant").asText(null));

        // Dedupe: first delivery inserts the row; a redelivery of an already processed event is skipped.
        jdbc.update("INSERT INTO public.razorpay_webhook_events (event_id, event_type, tenant, payload) "
            + "VALUES (?, ?, ?, ?) ON CONFLICT (event_id) DO NOTHING", eventId, eventType, tenant,
            new String(raw, StandardCharsets.UTF_8));
        List<Object> processed = jdbc.queryForList("SELECT processed_at FROM public.razorpay_webhook_events WHERE event_id = ?",
            Object.class, eventId);
        if (!processed.isEmpty() && processed.get(0) != null) {
            log.info("Duplicate Razorpay event {} ({}) ignored", eventId, eventType);
            return ResponseEntity.ok(Map.of("status", "duplicate"));
        }

        String outcome;
        try {
            outcome = process(eventType, link, payload, eventId, tenant);
        } catch (Exception e) {
            log.error("Razorpay event {} ({}) failed: {}", eventId, eventType, e.toString(), e);
            return ResponseEntity.status(500).body(Map.of("error", "processing_failed"));
        }
        jdbc.update("UPDATE public.razorpay_webhook_events SET processed_at = NOW() WHERE event_id = ?", eventId);
        return ResponseEntity.ok(Map.of("status", outcome));
    }

    private String process(String eventType, JsonNode link, JsonNode payload, String eventId, String tenant) {
        if (tenant == null) return "ignored_unknown_tenant";
        if (eventType.equals("order.paid")) return ecomOrderPaid(payload, tenant);
        String linkId = link.path("id").asText(null);
        if (linkId == null || !eventType.startsWith("payment_link.")) return "ignored";
        schemaInitializer.ensureSchema(tenant);
        return tenantWork.in(tenant, () -> switch (eventType) {
                case "payment_link.paid" -> {
                    JsonNode payment = payload.path("payload").path("payment").path("entity");
                    long amountPaid = link.path("amount_paid").asLong(payment.path("amount").asLong(-1));
                    String currency = link.path("currency").asText(payment.path("currency").asText(""));
                    Instant paidAt = payment.hasNonNull("created_at") ? Instant.ofEpochSecond(payment.get("created_at").asLong()) : null;
                    yield paymentLinkService.handlePaid(linkId, amountPaid, currency, payment.path("id").asText(null),
                        paidAt, eventId).name().toLowerCase();
                }
                case "payment_link.expired" -> paymentLinkService.handleClosed(linkId, "expired", eventId).name().toLowerCase();
                case "payment_link.cancelled" -> paymentLinkService.handleClosed(linkId, "cancelled", eventId).name().toLowerCase();
                default -> "ignored";
            });
    }

    /**
     * Website checkout order paid (Razorpay Orders API). Backs up the browser's verify call, e.g. when she closed
     * the page right after paying. Only orders we created for the website (notes.source = ecom) are touched.
     */
    private String ecomOrderPaid(JsonNode payload, String tenant) {
        JsonNode order = payload.path("payload").path("order").path("entity");
        JsonNode payment = payload.path("payload").path("payment").path("entity");
        String orderId = order.path("id").asText(null);
        if (orderId == null || !"ecom".equals(order.path("notes").path("source").asText(""))) return "ignored_not_ecom";
        Long expected = ecomPaymentService.amountPaise(tenant, orderId);
        if (expected == null) return "ignored_unknown_order";
        long paid = order.path("amount_paid").asLong(payment.path("amount").asLong(-1));
        if (paid != expected || !"INR".equalsIgnoreCase(order.path("currency").asText(""))) {
            log.error("ECOM_PAYMENT_AMOUNT_MISMATCH order={} expected={} paid={} - not marked paid", orderId, expected, paid);
            return "needs_review";
        }
        Integer internal = ecomPaymentService.markPaid(tenant, orderId, payment.path("id").asText(null), null);
        return internal == null ? "already_paid" : "ecom_paid";
    }

    /** Firm schema from the link's notes (set when we created it); must be a registered firm. */
    private String resolveTenant(String fromNotes) {
        String candidate = fromNotes != null && !fromNotes.isBlank() && !"null".equals(fromNotes) ? fromNotes : defaultTenant;
        return firmRepository.existsBySchemaName(candidate) ? candidate : null;
    }

    private static byte[] sha256(byte[] data) {
        try { return java.security.MessageDigest.getInstance("SHA-256").digest(data); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}

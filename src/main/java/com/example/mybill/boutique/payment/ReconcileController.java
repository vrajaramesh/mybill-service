package com.example.mybill.boutique.payment;

import com.example.mybill.boutique.config.AgentTokenVerifier;
import com.example.mybill.boutique.config.BoutiqueSchemaInitializer;
import com.example.mybill.dto.Firm;
import com.example.mybill.repository.FirmRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * POST /internal/jobs/reconcile-payments - run by Cloud Scheduler every 10 minutes (TenantFilter skips /internal/).
 * Requires a Google ID token (audience agent.api.audience = this service's URL) whose email is
 * reconcile.allowed-sa-email. For every firm: open links older than 5 minutes are fetched from Razorpay and a
 * missed "paid" (or expiry) is applied with the same idempotent handlers as the webhook.
 */
@RestController
public class ReconcileController {

    private static final Logger log = LoggerFactory.getLogger(ReconcileController.class);
    private static final Set<String> GOOGLE_ISSUERS = Set.of("accounts.google.com", "https://accounts.google.com");

    @Value("${reconcile.allowed-sa-email:}") private String allowedEmail;
    @Value("${agent.api.audience:}") private String audience;
    @Value("${reconcile.auth-enabled:true}") private boolean authEnabled;

    @Autowired private AgentTokenVerifier tokenVerifier;
    @Autowired private FirmRepository firmRepository;
    @Autowired private BoutiqueSchemaInitializer schemaInitializer;
    @Autowired private PaymentLinkService paymentLinkService;
    @Autowired private RazorpayPaymentLinkClient razorpay;
    @Autowired private TenantWork tenantWork;

    @PostMapping("/internal/jobs/reconcile-payments")
    public ResponseEntity<Map<String, Object>> reconcile(@RequestHeader(value = "Authorization", required = false) String authorization) {
        if (authEnabled) {
            if (allowedEmail.isBlank() || audience.isBlank()) {
                return ResponseEntity.status(503).body(Map.of("error", "not_configured"));
            }
            if (authorization == null || !authorization.startsWith("Bearer ")) {
                return ResponseEntity.status(401).body(Map.of("error", "unauthorized"));
            }
            var token = tokenVerifier.verify(authorization.substring(7).trim(), audience);
            if (token.isEmpty() || !GOOGLE_ISSUERS.contains(token.get().issuer())) {
                return ResponseEntity.status(401).body(Map.of("error", "unauthorized"));
            }
            if (!token.get().emailVerified() || !allowedEmail.equalsIgnoreCase(String.valueOf(token.get().email()))) {
                return ResponseEntity.status(403).body(Map.of("error", "forbidden"));
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        int checked = 0, paid = 0, closed = 0, errors = 0;
        for (Firm firm : firmRepository.findAll()) {
            String schema = firm.getSchemaName();
            if (schema == null || schema.isBlank()) continue;
            try {
                schemaInitializer.ensureSchema(schema);
                for (String linkId : tenantWork.in(schema, paymentLinkService::openLinkIdsForReconciliation)) {
                    checked++;
                    try {
                        JsonNode link = razorpay.fetchPaymentLink(linkId);
                        String status = link.path("status").asText("");
                        String eventId = "reconcile:" + linkId;
                        if ("paid".equals(status)) {
                            JsonNode payment = firstCaptured(link.path("payments"));
                            var outcome = tenantWork.in(schema, () -> paymentLinkService.handlePaid(linkId,
                                link.path("amount_paid").asLong(-1), link.path("currency").asText("INR"),
                                payment == null ? null : payment.path("payment_id").asText(null),
                                payment != null && payment.hasNonNull("created_at") ? Instant.ofEpochSecond(payment.get("created_at").asLong()) : null,
                                eventId));
                            if (outcome == PaymentLinkService.Outcome.PAID) {
                                paid++;
                                log.warn("RECONCILED_MISSED_PAYMENT link={} firm={}", linkId, schema);
                            }
                        } else if ("expired".equals(status) || "cancelled".equals(status)) {
                            if (tenantWork.in(schema, () -> paymentLinkService.handleClosed(linkId, status, eventId))
                                == PaymentLinkService.Outcome.CLOSED) closed++;
                        }
                    } catch (Exception e) {
                        errors++;
                        log.error("Reconcile failed for link {} ({}): {}", linkId, schema, e.toString());
                    }
                }
            } catch (Exception e) {
                errors++;
                log.error("Reconcile failed for firm {}: {}", schema, e.toString());
            }
        }
        summary.put("checked", checked);
        summary.put("marked_paid", paid);
        summary.put("marked_closed", closed);
        summary.put("errors", errors);
        return ResponseEntity.ok(summary);
    }

    private static JsonNode firstCaptured(JsonNode payments) {
        if (payments == null || !payments.isArray()) return null;
        for (JsonNode p : payments) {
            if ("captured".equals(p.path("status").asText())) return p;
        }
        return payments.size() > 0 ? payments.get(0) : null;
    }
}

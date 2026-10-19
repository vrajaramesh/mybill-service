package com.example.mybill.boutique.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.IdTokenCredentials;
import com.google.auth.oauth2.IdTokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tells srisa-agent about payment events: POST {agent.events.url}/internal/events with a Google ID token
 * (audience agent.events.audience) from this service's runtime service account. Runs after the DB commit and
 * asynchronously, so Razorpay's webhook call is never kept waiting. 3 attempts with backoff, then ERROR log
 * (reconciliation + the agent's order status tool are the fallback).
 */
@Component
public class AgentEventNotifier {

    private static final Logger log = LoggerFactory.getLogger(AgentEventNotifier.class);

    @Value("${agent.events.url:}") private String eventsUrl;
    @Value("${agent.events.audience:}") private String audience;
    @Value("${agent.events.auth-enabled:true}") private boolean authEnabled;
    @Value("${agent.events.backoff-ms:1000}") private long backoffMs;

    @Autowired private ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAgentEvent(AgentEvent event) {
        send(event);
    }

    /** Returns true if srisa-agent accepted the event. */
    public boolean send(AgentEvent event) {
        if (eventsUrl == null || eventsUrl.isBlank()) {
            log.info("AGENT_EVENTS_URL not set; not notifying the agent about {} {}", event.event(), event.orderNo());
            return false;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("event", event.event());
        body.put("order_no", event.orderNo());
        body.put("customer_phone", event.customerPhone());
        body.put("customer_name", event.customerName());
        body.put("amount", event.amount());
        body.put("estimated_delivery_date", event.estimatedDeliveryDate() == null ? null : event.estimatedDeliveryDate().toString());
        body.put("event_id", event.eventId());
        body.put("exact_measurements_required", event.exactMeasurementsRequired());
        body.put("design_code", event.designCode());
        body.put("design_name", event.designName());
        String url = eventsUrl.replaceAll("/+$", "") + "/internal/events";
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
                if (authEnabled) request.header("Authorization", "Bearer " + idToken());
                HttpResponse<String> resp = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() / 100 == 2) {
                    log.info("Agent notified: {} {} (attempt {})", event.event(), event.orderNo(), attempt);
                    return true;
                }
                log.warn("Agent event {} {} rejected: HTTP {} (attempt {})", event.event(), event.orderNo(), resp.statusCode(), attempt);
            } catch (Exception e) {
                log.warn("Agent event {} {} failed (attempt {}): {}", event.event(), event.orderNo(), attempt, e.toString());
            }
            if (attempt < 3) sleep(backoffMs * (1L << (attempt - 1)));
        }
        log.error("AGENT_NOTIFY_FAILED event={} order={} event_id={} - customer not told automatically",
            event.event(), event.orderNo(), event.eventId());
        return false;
    }

    /** ID token for the agent's audience from Application Default Credentials (Cloud Run metadata server). */
    protected String idToken() throws Exception {
        GoogleCredentials credentials = GoogleCredentials.getApplicationDefault();
        if (!(credentials instanceof IdTokenProvider provider)) {
            throw new IllegalStateException("Default credentials cannot mint ID tokens (" + credentials.getClass().getSimpleName() + ")");
        }
        IdTokenCredentials token = IdTokenCredentials.newBuilder()
            .setIdTokenProvider(provider)
            .setTargetAudience(audience == null || audience.isBlank() ? eventsUrl : audience)
            .setOptions(java.util.List.of(IdTokenProvider.Option.FORMAT_FULL))
            .build();
        token.refresh();
        return token.getIdToken().getTokenValue();
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}

package com.example.mybill.boutique.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * Razorpay Payment Links REST API (https://razorpay.com/docs/api/payments/payment-links/).
 * Uses the same key pair as the ecom checkout (razorpay.key_id / razorpay.key_secret). The base URL is
 * configurable so tests can point it at a fake server; razorpay-java hard-codes the host.
 */
@Component
public class RazorpayPaymentLinkClient {

    /** Razorpay rejected the request (4xx) - retrying won't help. */
    public static class RazorpayRejected extends RuntimeException {
        public RazorpayRejected(String message) { super(message); }
    }

    /** Razorpay unreachable / 5xx - try again later. */
    public static class RazorpayUnavailable extends RuntimeException {
        public RazorpayUnavailable(String message, Throwable cause) { super(message, cause); }
    }

    private final RestClient http;
    private final ObjectMapper objectMapper;
    private final boolean configured;

    public RazorpayPaymentLinkClient(@Value("${razorpay.api.base-url:https://api.razorpay.com}") String baseUrl,
                                     @Value("${razorpay.key_id:}") String keyId,
                                     @Value("${razorpay.key_secret:}") String keySecret,
                                     ObjectMapper objectMapper) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(15_000);
        String basic = Base64.getEncoder().encodeToString((keyId + ":" + keySecret).getBytes(StandardCharsets.UTF_8));
        this.http = RestClient.builder()
            .baseUrl(baseUrl)
            .requestFactory(factory)
            .defaultHeader("Authorization", "Basic " + basic)
            .build();
        this.objectMapper = objectMapper;
        this.configured = !keyId.isBlank() && !keySecret.isBlank();
    }

    public boolean isConfigured() { return configured; }

    /** POST /v1/payment_links -> the created link ({id, short_url, status, expire_by, ...}). */
    public JsonNode createPaymentLink(Map<String, Object> body) {
        return call(() -> http.post().uri("/v1/payment_links").contentType(MediaType.APPLICATION_JSON)
            .body(body).retrieve().body(String.class));
    }

    /** GET /v1/payment_links/{id} -> {status, amount, amount_paid, payments: [...]}. */
    public JsonNode fetchPaymentLink(String linkId) {
        return call(() -> http.get().uri("/v1/payment_links/{id}", linkId).retrieve().body(String.class));
    }

    private JsonNode call(java.util.function.Supplier<String> request) {
        if (!configured) throw new RazorpayUnavailable("Razorpay keys are not configured", null);
        try {
            return objectMapper.readTree(request.get());
        } catch (RestClientResponseException e) {
            HttpStatusCode status = e.getStatusCode();
            if (status.is4xxClientError()) {
                throw new RazorpayRejected("Razorpay " + status.value() + ": " + e.getResponseBodyAsString());
            }
            throw new RazorpayUnavailable("Razorpay " + status.value(), e);
        } catch (ResourceAccessException e) {
            throw new RazorpayUnavailable("Razorpay unreachable: " + e.getMessage(), e);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RazorpayUnavailable("Unreadable Razorpay response", e);
        }
    }
}

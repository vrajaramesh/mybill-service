package com.example.mybill.wholesale.service;

import com.example.mybill.wholesale.entity.WholesaleInstagramSettings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;

/**
 * Instagram Messaging calls (Meta Graph API) for the wholesale inbox: send a text reply, read a sender's profile,
 * verify webhook signatures. Works with both graph.facebook.com (page token) and graph.instagram.com (Instagram token)
 * through the configured base URL; replies are sent to {base}/me/messages.
 */
@Component
public class WholesaleInstagramClient {

    /** Instagram rejects longer text messages. */
    public static final int MAX_TEXT = 1000;

    public static class SendException extends RuntimeException {
        public SendException(String message) { super(message); }
    }

    public record Profile(String username, String name) {}

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final ObjectMapper json = new ObjectMapper();

    /** Sends a text message; returns Instagram's message id. */
    public String sendText(WholesaleInstagramSettings settings, String recipientId, String text) {
        if (settings.getAccessToken() == null || settings.getAccessToken().isBlank()) {
            throw new SendException("Instagram access token is not configured");
        }
        String body = text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT - 3) + "..." : text;
        try {
            String payload = json.writeValueAsString(Map.of(
                "recipient", Map.of("id", recipientId),
                "message", Map.of("text", body)));
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(base(settings) + "/me/messages"))
                .header("Authorization", "Bearer " + settings.getAccessToken())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .timeout(Duration.ofSeconds(30))
                .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode node = resp.body() == null || resp.body().isBlank() ? json.createObjectNode() : json.readTree(resp.body());
            if (resp.statusCode() / 100 != 2) {
                String error = node.path("error").path("message").asText("");
                throw new SendException("Instagram API " + resp.statusCode() + (error.isBlank() ? "" : ": " + error));
            }
            return node.path("message_id").asText(null);
        } catch (SendException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SendException("Interrupted while sending to Instagram");
        } catch (Exception e) {
            throw new SendException("Could not reach Instagram: " + e.getMessage());
        }
    }

    /** Best effort: the sender's username and name (null when not permitted / unavailable). */
    public Profile profile(WholesaleInstagramSettings settings, String igUserId) {
        if (settings.getAccessToken() == null || settings.getAccessToken().isBlank()) return null;
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(base(settings) + "/" + URLEncoder.encode(igUserId, StandardCharsets.UTF_8) + "?fields=name,username"))
                .header("Authorization", "Bearer " + settings.getAccessToken())
                .GET().timeout(Duration.ofSeconds(15)).build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) return null;
            JsonNode node = json.readTree(resp.body());
            return new Profile(node.path("username").asText(null), node.path("name").asText(null));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Checks Meta's X-Hub-Signature-256 header ("sha256=<hex HMAC of the raw body with the app secret>"). */
    public static boolean validSignature(String appSecret, byte[] body, String header) {
        if (appSecret == null || appSecret.isBlank() || header == null || !header.startsWith("sha256=")) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal(body);
            byte[] given = HexFormat.of().parseHex(header.substring(7).trim());
            return MessageDigest.isEqual(expected, given);
        } catch (Exception e) {
            return false;
        }
    }

    private static String base(WholesaleInstagramSettings settings) {
        String b = settings.getApiBaseUrl() == null || settings.getApiBaseUrl().isBlank()
            ? "https://graph.facebook.com/v21.0" : settings.getApiBaseUrl().trim();
        return b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }
}

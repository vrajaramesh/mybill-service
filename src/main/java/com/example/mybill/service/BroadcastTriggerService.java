package com.example.mybill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Sends broadcast messages via the mybill-whatsapp Express API.
 * mybill-whatsapp must be running and listening on hermes.whatsapp.url.
 */
@Service
public class BroadcastTriggerService {

    @Value("${hermes.whatsapp.url:http://localhost:3001}")
    private String whatsappBaseUrl;

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json = new ObjectMapper();

    public record BroadcastResult(int sent, int failed) {}

    /**
     * POST /broadcast/{firmCode} on the mybill-whatsapp Express server.
     * Returns sent/failed counts. Non-throwing — logs errors and returns failed counts.
     */
    public BroadcastResult sendBroadcast(String firmCode, List<String> phones,
                                          String imageUrl, String caption) {
        if (phones == null || phones.isEmpty()) return new BroadcastResult(0, 0);

        try {
            ObjectNode body = json.createObjectNode();
            ArrayNode recipients = body.putArray("recipients");
            phones.forEach(recipients::add);
            body.put("caption", caption != null ? caption : "");
            if (imageUrl != null && !imageUrl.isBlank()) body.put("imageUrl", imageUrl);

            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(whatsappBaseUrl + "/broadcast/" + firmCode))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .timeout(Duration.ofMinutes(5))
                .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() == 200) {
                var result = json.readTree(resp.body());
                return new BroadcastResult(
                    result.path("sent").asInt(0),
                    result.path("failed").asInt(0)
                );
            } else {
                System.err.println("[Hermes] Broadcast HTTP " + resp.statusCode() + ": " + resp.body());
                return new BroadcastResult(0, phones.size());
            }
        } catch (Exception e) {
            System.err.println("[Hermes] Broadcast failed for " + firmCode + ": " + e.getMessage());
            return new BroadcastResult(0, phones.size());
        }
    }

    /**
     * Send a plain text WhatsApp message to a single phone (for owner daily reports).
     */
    public boolean sendTextToPhone(String firmCode, String phone, String message) {
        try {
            ObjectNode body = json.createObjectNode();
            body.put("phone", phone);
            body.put("message", message);

            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(whatsappBaseUrl + "/send/" + firmCode))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .timeout(Duration.ofSeconds(30))
                .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            System.err.println("[Hermes] sendTextToPhone failed: " + e.getMessage());
            return false;
        }
    }
}

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
import java.util.Map;

@Service
public class ClaudeService {

    @Value("${anthropic.api.key}")
    private String apiKey;

    @Value("${anthropic.api.url}")
    private String apiUrl;

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20)).build();
    private final ObjectMapper json = new ObjectMapper();

    private static final String MODEL   = "claude-sonnet-4-6";
    private static final String API_VER = "2023-06-01";

    /**
     * Single-turn completion — no history, no products. Used for analytics/summary tasks.
     * Returns Claude's reply text.
     */
    public String complete(String systemPrompt, String userPrompt) {
        try {
            ObjectNode body = json.createObjectNode();
            body.put("model",      MODEL);
            body.put("max_tokens", 1500);
            body.put("system",     systemPrompt);

            ArrayNode messages = body.putArray("messages");
            messages.addObject().put("role", "user").put("content", userPrompt);

            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .header("Content-Type",      "application/json")
                .header("x-api-key",         apiKey)
                .header("anthropic-version", API_VER)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .timeout(Duration.ofSeconds(60))
                .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200)
                throw new RuntimeException("Claude API " + resp.statusCode() + ": " + resp.body());

            return json.readTree(resp.body())
                .path("content").get(0).path("text").asText();

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Claude complete failed: " + e.getMessage(), e);
        }
    }

    /**
     * Send a chat turn to Claude with conversation history and matching products.
     * Returns Claude's reply text.
     */
    public String chat(String firmName,
                       String userMessage,
                       List<Map<String, String>> history,
                       List<ProductSearchResult> products) {
        try {
            ObjectNode body = json.createObjectNode();
            body.put("model", MODEL);
            body.put("max_tokens", 1024);
            body.put("system", buildSystemPrompt(firmName));

            ArrayNode messages = body.putArray("messages");

            // Conversation history (alternating user / assistant)
            for (Map<String, String> msg : history) {
                ObjectNode m = messages.addObject();
                m.put("role",    msg.get("role"));
                m.put("content", msg.get("content"));
            }

            // Current user turn — inject matching product context
            ObjectNode userTurn = messages.addObject();
            userTurn.put("role",    "user");
            userTurn.put("content", buildUserContent(userMessage, products));

            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .header("Content-Type",     "application/json")
                .header("x-api-key",        apiKey)
                .header("anthropic-version", API_VER)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .timeout(Duration.ofSeconds(40))
                .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200)
                throw new RuntimeException("Claude API " + resp.statusCode() + ": " + resp.body());

            return json.readTree(resp.body())
                .path("content").get(0).path("text").asText();

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Claude chat failed: " + e.getMessage(), e);
        }
    }

    private String buildSystemPrompt(String firmName) {
        return """
            You are a warm, knowledgeable fabric shopping assistant for %s.
            You specialize in Indian textiles — silks, cottons, georgettes, chiffons,
            linens, and more. You understand occasions (weddings, festivals, casual, formal),
            garments (sarees, lehengas, kurtas, blouses, suits), and can recommend fabrics
            by texture, drape, and purpose.

            Guidelines:
            - Keep responses concise and conversational (2–4 sentences).
            - When relevant products are provided, mention them by name and briefly explain
              why they suit the customer's need.
            - If no matching products exist, say so honestly and suggest what to look for.
            - Ask one helpful follow-up question when it helps narrow down options.
            - Prices are in Indian Rupees (₹) per meter unless otherwise noted.
            - Never invent product details not provided to you.
            """.formatted(firmName);
    }

    private String buildUserContent(String userMessage, List<ProductSearchResult> products) {
        if (products == null || products.isEmpty()) {
            return userMessage;
        }
        StringBuilder sb = new StringBuilder(userMessage);
        sb.append("\n\n[Matching products from our catalog:]\n");
        for (int i = 0; i < products.size(); i++) {
            ProductSearchResult p = products.get(i);
            sb.append(i + 1).append(". ")
              .append(p.getProductName())
              .append(" — ₹").append(p.getSellingPrice()).append("/meter");
            if (p.getCategoryName() != null)
                sb.append(" | Category: ").append(p.getCategoryName());
            if (p.getTags() != null && !p.getTags().isBlank())
                sb.append(" | Occasions: ").append(p.getTags());
            sb.append("\n");
        }
        return sb.toString();
    }
}

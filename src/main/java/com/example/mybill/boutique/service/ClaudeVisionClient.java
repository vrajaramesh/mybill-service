package com.example.mybill.boutique.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * Anthropic Messages API (POST https://api.anthropic.com/v1/messages) for the dropship intake: text and image
 * (public URL) content, strict-JSON answers. Key from ANTHROPIC_API_KEY (property anthropic.api.key); the model is
 * dropship.ai.model (default Claude Haiku 5.5; set DROPSHIP_AI_MODEL=claude-sonnet-5-5 to switch).
 */
@Component
public class ClaudeVisionClient {

    private static final Logger log = LoggerFactory.getLogger(ClaudeVisionClient.class);
    private static final String API_VERSION = "2023-06-01";

    /** The model didn't answer with parseable JSON (twice). */
    public static class BadJson extends RuntimeException {
        public BadJson(String message) { super(message); }
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final ObjectMapper json;
    private final String apiKey;
    private final String apiUrl;
    private final String model;

    public ClaudeVisionClient(ObjectMapper json,
                              @Value("${anthropic.api.key:}") String apiKey,
                              @Value("${anthropic.api.url:https://api.anthropic.com/v1/messages}") String apiUrl,
                              @Value("${dropship.ai.model:claude-haiku-5-5}") String model) {
        this.json = json;
        this.apiKey = apiKey;
        this.apiUrl = apiUrl;
        this.model = model;
    }

    public String model() { return model; }

    public static Map<String, Object> text(String text) {
        return Map.of("type", "text", "text", text);
    }

    public static Map<String, Object> imageUrl(String url) {
        return Map.of("type", "image", "source", Map.of("type", "url", "url", url));
    }

    /**
     * One user turn -> the JSON the model answered (a code fence around it is tolerated). Retries once when the answer
     * isn't valid JSON, telling the model so; then throws {@link BadJson}.
     */
    public JsonNode json(String system, List<Map<String, Object>> content, int maxTokens) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", content));
        String answer = call(system, messages, maxTokens);
        try {
            return parse(answer);
        } catch (Exception first) {
            log.warn("DROPSHIP_AI_BAD_JSON (retrying once): {}", abbreviate(answer));
            messages.add(Map.of("role", "assistant", "content", answer == null || answer.isBlank() ? "(empty)" : answer));
            messages.add(Map.of("role", "user", "content",
                "That was not valid JSON. Reply again with ONLY the JSON - no explanation, no code fence."));
            String retry = call(system, messages, maxTokens);
            try {
                return parse(retry);
            } catch (Exception second) {
                throw new BadJson("The AI did not return valid JSON: " + abbreviate(retry));
            }
        }
    }

    private String call(String system, List<Map<String, Object>> messages, int maxTokens) {
        if (apiKey == null || apiKey.isBlank()) throw new IllegalStateException("ANTHROPIC_API_KEY is not set");
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("max_tokens", maxTokens);
            body.put("system", system);
            body.put("messages", messages);
            HttpRequest req = HttpRequest.newBuilder(URI.create(apiUrl))
                .header("Content-Type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", API_VERSION)
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new IllegalStateException("Claude API " + resp.statusCode() + ": " + abbreviate(resp.body()));
            }
            JsonNode root = json.readTree(resp.body());
            StringBuilder out = new StringBuilder();
            for (JsonNode block : root.path("content")) {
                if ("text".equals(block.path("type").asText())) out.append(block.path("text").asText());
            }
            if ("refusal".equals(root.path("stop_reason").asText())) throw new IllegalStateException("Claude refused the request");
            return out.toString();
        } catch (RuntimeException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Claude API call interrupted", e);
        } catch (Exception e) {
            throw new IllegalStateException("Claude API call failed: " + e.getMessage(), e);
        }
    }

    private JsonNode parse(String answer) throws Exception {
        String t = answer == null ? "" : answer.trim();
        if (t.startsWith("```")) t = t.replaceFirst("^```[a-zA-Z]*\\s*", "").replaceFirst("\\s*```\\s*$", "");
        int start = Math.min(t.indexOf('{') < 0 ? Integer.MAX_VALUE : t.indexOf('{'), t.indexOf('[') < 0 ? Integer.MAX_VALUE : t.indexOf('['));
        if (start == Integer.MAX_VALUE) throw new IllegalArgumentException("no JSON");
        return json.readTree(t.substring(start));
    }

    private static String abbreviate(String s) {
        return s == null ? "" : s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}

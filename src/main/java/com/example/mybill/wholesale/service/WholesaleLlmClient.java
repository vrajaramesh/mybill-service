package com.example.mybill.wholesale.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Wholesale-only LLM client (Anthropic Messages API with tool use). Independent of the retail ClaudeService:
 * own model / key / URL settings (wholesale.ai.*), falling back to the shared Anthropic key.
 * The model never gets database access — it can only ask for the tools passed in {@link #create}.
 */
@Component
public class WholesaleLlmClient {

    public record Tool(String name, String description, JsonNode inputSchema) {}

    public static class LlmException extends RuntimeException {
        public LlmException(String message) { super(message); }
    }

    @Value("${wholesale.ai.api-key:${anthropic.api.key:}}")
    private String apiKey;

    @Value("${wholesale.ai.api-url:${anthropic.api.url:https://api.anthropic.com/v1/messages}}")
    private String apiUrl;

    @Value("${wholesale.ai.model:claude-sonnet-5-5}")
    private String model;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    private final ObjectMapper json = new ObjectMapper();

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String model() {
        return model;
    }

    /**
     * One Messages API call. {@code messages} is the full conversation in API format (user / assistant turns with
     * text, tool_use and tool_result blocks). Returns the response body (content blocks + stop_reason).
     */
    public JsonNode create(String system, ArrayNode messages, List<Tool> tools, int maxTokens) {
        if (!isConfigured()) throw new LlmException("Wholesale AI is not configured (wholesale.ai.api-key / anthropic.api.key)");
        ObjectNode body = json.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        body.put("system", system);
        body.set("messages", messages);
        if (tools != null && !tools.isEmpty()) {
            ArrayNode t = body.putArray("tools");
            for (Tool tool : tools) {
                t.addObject().put("name", tool.name()).put("description", tool.description()).set("input_schema", tool.inputSchema());
            }
        }
        String payload;
        try {
            payload = json.writeValueAsString(body);
        } catch (Exception e) {
            throw new LlmException("Could not build the AI request: " + e.getMessage());
        }

        for (int attempt = 1; ; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .timeout(Duration.ofSeconds(90))
                    .build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                int code = resp.statusCode();
                if (code == 200) return json.readTree(resp.body());
                boolean retryable = code == 429 || code == 529 || code >= 500;
                if (!retryable || attempt >= 3) {
                    String detail = "";
                    try { detail = json.readTree(resp.body()).path("error").path("message").asText(""); } catch (Exception ignored) { }
                    throw new LlmException("AI service error " + code + (detail.isBlank() ? "" : ": " + detail));
                }
                Thread.sleep(1500L * attempt);
            } catch (LlmException e) {
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LlmException("Interrupted while waiting for the AI service");
            } catch (Exception e) {
                if (attempt >= 3) throw new LlmException("AI service unreachable: " + e.getMessage());
                try { Thread.sleep(1500L * attempt); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new LlmException("Interrupted while waiting for the AI service");
                }
            }
        }
    }
}

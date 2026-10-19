package com.example.mybill.boutique.service;

import com.example.mybill.boutique.exception.AgentApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.IdTokenCredentials;
import com.google.auth.oauth2.IdTokenProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Calls srisa-agent's staff inbox API (/staff/**) with a Google ID token of this service's runtime service account
 * (the same identity and audience as the payment events). Errors become AgentApiException for the UI.
 */
@Component
public class AgentStaffClient {

    @Value("${agent.events.url:}") private String agentUrl;
    @Value("${agent.events.audience:}") private String audience;
    @Value("${agent.events.auth-enabled:true}") private boolean authEnabled;

    @Autowired private ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public JsonNode get(String path) {
        return send(HttpRequest.newBuilder(uri(path)).GET(), path);
    }

    public JsonNode post(String path, Map<String, ?> body) {
        try {
            return send(HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body))), path);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private URI uri(String path) {
        if (agentUrl == null || agentUrl.isBlank()) {
            throw new AgentApiException(HttpStatus.SERVICE_UNAVAILABLE, "AGENT_NOT_CONFIGURED", "AGENT_EVENTS_URL is not set", null);
        }
        return URI.create(agentUrl.replaceAll("/+$", "") + path);
    }

    private JsonNode send(HttpRequest.Builder builder, String path) {
        HttpResponse<String> resp;
        try {
            builder.timeout(Duration.ofSeconds(30));
            if (authEnabled) builder.header("Authorization", "Bearer " + idToken());
            resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new AgentApiException(HttpStatus.SERVICE_UNAVAILABLE, "AGENT_UNAVAILABLE", "Chat service unavailable", null);
        }
        JsonNode json;
        try {
            json = resp.body() == null || resp.body().isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(resp.body());
        } catch (Exception e) {
            json = objectMapper.createObjectNode();
        }
        if (resp.statusCode() / 100 == 2) return json;
        JsonNode detail = json.path("detail");
        String code = detail.path("code").asText(resp.statusCode() == 404 ? "CHAT_NOT_FOUND" : "AGENT_ERROR");
        String message = detail.path("message").asText(detail.isTextual() ? detail.asText() : "Chat service error");
        HttpStatus status = switch (resp.statusCode()) {
            case 404 -> HttpStatus.NOT_FOUND;
            case 409 -> HttpStatus.CONFLICT;
            case 422, 400 -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.BAD_GATEWAY;
        };
        throw new AgentApiException(status, code, message, Map.of("path", path));
    }

    protected String idToken() throws Exception {
        GoogleCredentials credentials = GoogleCredentials.getApplicationDefault();
        if (!(credentials instanceof IdTokenProvider provider)) {
            throw new IllegalStateException("Default credentials cannot mint ID tokens");
        }
        IdTokenCredentials token = IdTokenCredentials.newBuilder().setIdTokenProvider(provider)
            .setTargetAudience(audience == null || audience.isBlank() ? agentUrl : audience)
            .setOptions(java.util.List.of(IdTokenProvider.Option.FORMAT_FULL)).build();
        token.refresh();
        return token.getIdToken().getTokenValue();
    }
}

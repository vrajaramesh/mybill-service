package com.example.mybill.boutique.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Cloud Scheduler calls to /internal/jobs/*: a Google ID token (audience agent.api.audience = this service's URL)
 * whose email is reconcile.allowed-sa-email (the mybill-scheduler service account).
 */
@Component
public class SchedulerJobAuth {

    private static final Set<String> GOOGLE_ISSUERS = Set.of("accounts.google.com", "https://accounts.google.com");

    @Value("${reconcile.allowed-sa-email:}") private String allowedEmail;
    @Value("${agent.api.audience:}") private String audience;
    @Value("${reconcile.auth-enabled:true}") private boolean authEnabled;

    @Autowired private AgentTokenVerifier tokenVerifier;

    /** Empty = allowed; otherwise the error response to return. */
    public Optional<ResponseEntity<Map<String, Object>>> reject(String authorization) {
        if (!authEnabled) return Optional.empty();
        if (allowedEmail.isBlank() || audience.isBlank()) {
            return Optional.of(ResponseEntity.status(503).body(Map.of("error", "not_configured")));
        }
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return Optional.of(ResponseEntity.status(401).body(Map.of("error", "unauthorized")));
        }
        var token = tokenVerifier.verify(authorization.substring(7).trim(), audience);
        if (token.isEmpty() || !GOOGLE_ISSUERS.contains(token.get().issuer())) {
            return Optional.of(ResponseEntity.status(401).body(Map.of("error", "unauthorized")));
        }
        if (!token.get().emailVerified() || !allowedEmail.equalsIgnoreCase(String.valueOf(token.get().email()))) {
            return Optional.of(ResponseEntity.status(403).body(Map.of("error", "forbidden")));
        }
        return Optional.empty();
    }
}

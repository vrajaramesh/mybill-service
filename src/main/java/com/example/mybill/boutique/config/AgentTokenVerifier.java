package com.example.mybill.boutique.config;

import com.google.api.client.json.webtoken.JsonWebSignature;
import com.google.auth.oauth2.TokenVerifier;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Verifies a Google-signed ID token: RS256 signature against Google's published certificates (fetched and
 * cached by google-auth-library), expiry and audience. Issuer / email checks are done by AgentAuthFilter.
 * Uses google-auth-library's TokenVerifier, already on the classpath via google-cloud-storage.
 */
@Component
public class AgentTokenVerifier {

    /** The claims AgentAuthFilter needs from a verified token. */
    public record VerifiedToken(String issuer, String email, boolean emailVerified) {}

    private volatile TokenVerifier verifier;
    private volatile String verifierAudience;

    /** Empty if the token is malformed, expired, not signed by Google or for another audience. */
    public Optional<VerifiedToken> verify(String token, String audience) {
        try {
            JsonWebSignature jws = verifierFor(audience).verify(token);
            JsonWebSignature.Payload payload = jws.getPayload();
            Object verified = payload.get("email_verified");
            boolean emailVerified = Boolean.TRUE.equals(verified) || "true".equals(String.valueOf(verified));
            return Optional.of(new VerifiedToken(payload.getIssuer(), (String) payload.get("email"), emailVerified));
        } catch (TokenVerifier.VerificationException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private TokenVerifier verifierFor(String audience) {
        if (verifier == null || !audience.equals(verifierAudience)) {
            synchronized (this) {
                if (verifier == null || !audience.equals(verifierAudience)) {
                    verifier = TokenVerifier.newBuilder().setAudience(audience).build();
                    verifierAudience = audience;
                }
            }
        }
        return verifier;
    }
}

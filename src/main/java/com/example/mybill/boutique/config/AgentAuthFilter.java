package com.example.mybill.boutique.config;

import com.example.mybill.boutique.dto.AgentError;
import com.example.mybill.multitenancy.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Guards /api/agent/** (TenantFilter skips this path):
 *   1. requires "Authorization: Bearer <Google ID token>" for the configured service account:
 *      Google signature, issuer accounts.google.com, audience = agent.api.audience,
 *      email = agent.allowed-sa-email and email_verified. Missing/invalid token → 401, other account → 403.
 *   2. sets the tenant to agent.tenant-schema (the agent always works for one firm; it has no app login,
 *      so the per-user JWT tenant resolution does not apply) and makes sure the boutique tables exist.
 * Verification can be switched off (agent.auth.enabled=false) only with the "local" profile.
 */
@Component
@Order(0)
public class AgentAuthFilter extends OncePerRequestFilter {

    public static final String PATH_PREFIX = "/api/agent/";
    private static final Set<String> GOOGLE_ISSUERS = Set.of("accounts.google.com", "https://accounts.google.com");

    @Value("${agent.auth.enabled:true}") private boolean authEnabled;
    @Value("${agent.api.audience:}") private String audience;
    @Value("${agent.allowed-sa-email:}") private String allowedEmail;
    @Value("${agent.tenant-schema:firm_srisa}") private String tenantSchema;

    @Autowired private AgentTokenVerifier tokenVerifier;
    @Autowired private BoutiqueSchemaInitializer schemaInitializer;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private Environment environment;

    @PostConstruct
    void checkConfiguration() {
        if (!authEnabled && !environment.acceptsProfiles(Profiles.of("local"))) {
            throw new IllegalStateException(
                "agent.auth.enabled=false is only allowed with the 'local' profile. Remove it or set it to true.");
        }
        if (authEnabled && (audience.isBlank() || allowedEmail.isBlank())) {
            System.err.println("[AgentAuth] AGENT_API_AUDIENCE / AGENT_ALLOWED_SA_EMAIL not set: /api/agent/** will answer 503.");
        }
        if (!authEnabled) {
            System.err.println("[AgentAuth] WARNING: token verification for /api/agent/** is DISABLED (local profile).");
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (authEnabled) {
            if (audience.isBlank() || allowedEmail.isBlank()) {
                write(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "AGENT_API_NOT_CONFIGURED",
                    "Agent API is not configured on this server");
                return;
            }
            String header = request.getHeader("Authorization");
            if (header == null || !header.startsWith("Bearer ") || header.length() <= 7) {
                write(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED",
                    "Missing Authorization: Bearer <Google ID token>");
                return;
            }
            Optional<AgentTokenVerifier.VerifiedToken> token = tokenVerifier.verify(header.substring(7).trim(), audience);
            if (token.isEmpty() || !GOOGLE_ISSUERS.contains(token.get().issuer())) {
                write(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED",
                    "ID token is invalid, expired or for another audience");
                return;
            }
            if (!token.get().emailVerified() || !allowedEmail.equalsIgnoreCase(String.valueOf(token.get().email()))) {
                write(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN",
                    "This service account is not allowed to call the agent API");
                return;
            }
        }

        try {
            schemaInitializer.ensureSchema(tenantSchema);
        } catch (Exception e) {
            System.err.println("[AgentAuth] Tenant " + tenantSchema + " unavailable: " + e.getMessage());
            write(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "TENANT_UNAVAILABLE",
                "The agent's firm is not available");
            return;
        }

        TenantContext.setCurrentTenant(tenantSchema);
        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private void write(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        objectMapper.writeValue(response.getWriter(), new AgentError(code, message, Map.of()));
    }
}

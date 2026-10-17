package com.example.mybill.boutique;

import com.example.mybill.boutique.config.AgentAuthFilter;
import com.example.mybill.boutique.config.AgentTokenVerifier;
import com.example.mybill.boutique.config.AgentTokenVerifier.VerifiedToken;
import com.example.mybill.boutique.config.BoutiqueSchemaInitializer;
import com.example.mybill.multitenancy.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Unit tests for /api/agent/** authentication (no database or Docker needed). */
class AgentAuthFilterTest {

    private static final String AUDIENCE = "https://mybill-service-276371417285.us-central1.run.app";
    private static final String AGENT_SA = "srisa-agent-sa@project.iam.gserviceaccount.com";

    private final AgentTokenVerifier verifier = mock(AgentTokenVerifier.class);
    private final BoutiqueSchemaInitializer schemaInitializer = mock(BoutiqueSchemaInitializer.class);
    private final AtomicReference<String> tenantSeenByController = new AtomicReference<>();
    private final FilterChain chain = (req, res) -> tenantSeenByController.set(TenantContext.getCurrentTenant());
    private AgentAuthFilter filter;

    @BeforeEach
    void setUp() {
        filter = newFilter(true, "default");
    }

    private AgentAuthFilter newFilter(boolean enabled, String profile) {
        AgentAuthFilter f = new AgentAuthFilter();
        ReflectionTestUtils.setField(f, "authEnabled", enabled);
        ReflectionTestUtils.setField(f, "audience", AUDIENCE);
        ReflectionTestUtils.setField(f, "allowedEmail", AGENT_SA);
        ReflectionTestUtils.setField(f, "tenantSchema", "firm_srisa");
        ReflectionTestUtils.setField(f, "tokenVerifier", verifier);
        ReflectionTestUtils.setField(f, "schemaInitializer", schemaInitializer);
        ReflectionTestUtils.setField(f, "objectMapper", new ObjectMapper());
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        ReflectionTestUtils.setField(f, "environment", environment);
        return f;
    }

    private MockHttpServletResponse call(String authorization) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/agent/size-chart");
        if (authorization != null) request.addHeader("Authorization", authorization);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    private void verifierReturns(String issuer, String email, boolean verified) {
        when(verifier.verify(eq("tok"), eq(AUDIENCE))).thenReturn(Optional.of(new VerifiedToken(issuer, email, verified)));
    }

    @Test
    void missingToken_is401() throws Exception {
        MockHttpServletResponse r = call(null);
        assertThat(r.getStatus()).isEqualTo(401);
        assertThat(r.getContentAsString()).contains("\"code\":\"UNAUTHORIZED\"");
        assertThat(tenantSeenByController.get()).isNull();
    }

    @Test
    void invalidToken_is401() throws Exception {
        when(verifier.verify(anyString(), anyString())).thenReturn(Optional.empty());
        assertThat(call("Bearer junk").getStatus()).isEqualTo(401);
    }

    @Test
    void nonGoogleIssuer_is401() throws Exception {
        verifierReturns("https://evil.example.com", AGENT_SA, true);
        assertThat(call("Bearer tok").getStatus()).isEqualTo(401);
    }

    @Test
    void otherServiceAccount_is403() throws Exception {
        verifierReturns("https://accounts.google.com", "someone@project.iam.gserviceaccount.com", true);
        MockHttpServletResponse r = call("Bearer tok");
        assertThat(r.getStatus()).isEqualTo(403);
        assertThat(r.getContentAsString()).contains("\"code\":\"FORBIDDEN\"");
    }

    @Test
    void unverifiedEmail_is403() throws Exception {
        verifierReturns("accounts.google.com", AGENT_SA, false);
        assertThat(call("Bearer tok").getStatus()).isEqualTo(403);
    }

    @Test
    void validToken_setsAgentTenantForTheRequestOnly() throws Exception {
        verifierReturns("accounts.google.com", AGENT_SA, true);
        MockHttpServletResponse r = call("Bearer tok");
        assertThat(r.getStatus()).isEqualTo(200);
        assertThat(tenantSeenByController.get()).isEqualTo("firm_srisa");
        assertThat(TenantContext.getCurrentTenant()).isNull();
        verify(schemaInitializer).ensureSchema("firm_srisa");
    }

    @Test
    void notConfigured_is503() throws Exception {
        ReflectionTestUtils.setField(filter, "audience", "");
        assertThat(call("Bearer tok").getStatus()).isEqualTo(503);
        verifyNoInteractions(verifier);
    }

    @Test
    void otherPaths_areNotFiltered() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/products");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(200);
        verifyNoInteractions(verifier, schemaInitializer);
    }

    @Test
    void disabledAuth_onlyAllowedWithLocalProfile() throws Exception {
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(newFilter(false, "default"), "checkConfiguration"))
            .isInstanceOf(IllegalStateException.class);

        filter = newFilter(false, "local");
        ReflectionTestUtils.invokeMethod(filter, "checkConfiguration");
        assertThat(call(null).getStatus()).isEqualTo(200);
        assertThat(tenantSeenByController.get()).isEqualTo("firm_srisa");
    }
}

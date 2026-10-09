package com.example.mybill.wholesale.config;

import com.example.mybill.multitenancy.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Guards /api/wholesale/**: requires a firm tenant (TenantFilter has already validated the JWT)
 * and makes sure the firm's wholesale tables exist before the request reaches a controller.
 */
@Component
public class WholesaleSchemaInterceptor implements HandlerInterceptor {

    @Autowired private WholesaleSchemaInitializer schemaInitializer;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;

        String schema = TenantContext.getCurrentTenant();
        if (schema == null || schema.isBlank() || "public".equals(schema)) {
            writeError(response, HttpServletResponse.SC_FORBIDDEN, "Wholesale requires a firm login");
            return false;
        }
        try {
            schemaInitializer.ensureSchema(schema);
        } catch (Exception e) {
            writeError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Wholesale module is not available for this firm");
            return false;
        }
        return true;
    }

    private void writeError(HttpServletResponse response, int status, String message) throws Exception {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"message\":\"" + message + "\"}");
    }
}

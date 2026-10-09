package com.example.mybill.wholesale.config;

import com.example.mybill.multitenancy.JwtUtil;
import com.example.mybill.wholesale.exception.WholesaleException;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Role checks for sensitive wholesale settings. TenantFilter has already validated the JWT;
 * this only reads its claims.
 */
@Component
public class WholesaleAccess {

    @Autowired private JwtUtil jwtUtil;

    /** Requires a firm ADMIN; returns the username for audit columns. */
    public String requireAdmin(HttpServletRequest request) {
        Claims claims = claims(request);
        if (!"ADMIN".equals(claims.get("role", String.class))) {
            throw new WholesaleException(HttpStatus.FORBIDDEN, "Only an admin can change wholesale settings");
        }
        return claims.getSubject();
    }

    /** Username of the caller (JWT subject), for created_by / issued_by audit columns. */
    public String username(HttpServletRequest request) {
        return claims(request).getSubject();
    }

    private Claims claims(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            throw new WholesaleException(HttpStatus.UNAUTHORIZED, "Missing or invalid Authorization header");
        }
        return jwtUtil.extractClaims(header.substring(7));
    }
}

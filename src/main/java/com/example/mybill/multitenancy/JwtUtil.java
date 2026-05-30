package com.example.mybill.multitenancy;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

@Component
public class JwtUtil {

    @Value("${app.jwt.secret}")
    private String secret;

    @Value("${app.jwt.expiry-days:7}")
    private int expiryDays;

    private SecretKey key() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateToken(String username, String schemaName, Long firmId, String role) {
        return Jwts.builder()
            .subject(username)
            .claim("schemaName", schemaName)
            .claim("firmId", firmId)
            .claim("role", role)
            .issuedAt(new Date())
            .expiration(Date.from(Instant.now().plus(expiryDays, ChronoUnit.DAYS)))
            .signWith(key())
            .compact();
    }

    public Claims extractClaims(String token) {
        return Jwts.parser().verifyWith(key()).build()
            .parseSignedClaims(token).getPayload();
    }

    public boolean isValid(String token) {
        try {
            extractClaims(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}

package com.example.mybill.boutique.controller;

import com.example.mybill.multitenancy.TenantContext;
import com.example.mybill.wholesale.config.WholesaleSchemaInitializer;
import com.example.mybill.wholesale.service.WholesaleAgentGateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Wholesale tools for the WhatsApp AI agent (wholesale chats of the firm in X-Agent-Tenant, e.g. firm_srihitha).
 * Same guard as the rest of /api/agent/** (Google ID token of the agent's service account).
 */
@RestController
@RequestMapping("/api/agent/wholesale")
public class AgentWholesaleController {

    @Autowired private WholesaleAgentGateway gateway;
    @Autowired private WholesaleSchemaInitializer schemaInitializer;
    @Autowired private ObjectMapper objectMapper;

    /** Body: {"input": {...tool input}, "phone": "+91...", "context": {...}} -> {"result", "context", "attention"}. */
    @PostMapping("/tools/{name}")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> tool(@PathVariable String name, @RequestBody Map<String, Object> body) {
        schemaInitializer.ensureSchema(TenantContext.getCurrentTenant());
        if (!WholesaleAgentGateway.TOOLS.contains(name)) {
            return ResponseEntity.badRequest().body(Map.of("error", "UNKNOWN_TOOL", "message", "Unknown wholesale tool " + name));
        }
        JsonNode input = objectMapper.valueToTree(body.getOrDefault("input", Map.of()));
        Map<String, Object> context = body.get("context") instanceof Map<?, ?> m
            ? new LinkedHashMap<>((Map<String, Object>) m) : new LinkedHashMap<>();
        String phone = body.get("phone") == null ? null : String.valueOf(body.get("phone"));
        return ResponseEntity.ok(gateway.run(name, input, phone, context, "WhatsApp AI"));
    }

    /** A product by its code (posts show SLW-<code>) with photo URLs; 404 if unknown or inactive. */
    @GetMapping("/products/by-code/{code}")
    public ResponseEntity<Map<String, Object>> byCode(@PathVariable String code) {
        schemaInitializer.ensureSchema(TenantContext.getCurrentTenant());
        return gateway.productByCode(code).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/products/{productId}/images")
    public Map<String, Object> images(@PathVariable Integer productId) {
        schemaInitializer.ensureSchema(TenantContext.getCurrentTenant());
        List<String> urls = gateway.images(productId);
        return Map.of("productId", productId, "images", urls);
    }
}

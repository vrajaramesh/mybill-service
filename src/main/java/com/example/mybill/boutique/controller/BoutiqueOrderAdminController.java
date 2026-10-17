package com.example.mybill.boutique.controller;

import com.example.mybill.boutique.config.BoutiqueSchemaInitializer;
import com.example.mybill.boutique.dto.OrderResponse;
import com.example.mybill.boutique.dto.OrderSummary;
import com.example.mybill.boutique.exception.AgentApiException;
import com.example.mybill.boutique.service.AgentOrderService;
import com.example.mybill.multitenancy.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Read-only boutique order screens in mybill-ui ("Boutique Orders"). Uses the normal app login (TenantFilter JWT),
 * so each firm sees its own orders - unlike /api/agent/**, which is Google-token-only and fixed to the agent's firm.
 */
@RestController
@RequestMapping("/api/boutique-orders")
public class BoutiqueOrderAdminController {

    @Autowired private AgentOrderService orderService;
    @Autowired private BoutiqueSchemaInitializer schemaInitializer;

    /** e.g. ?status=DRAFT&limit=50 (limit 1..100, newest first). */
    @GetMapping
    public List<OrderSummary> list(@RequestParam(required = false) String status,
                                   @RequestParam(defaultValue = "50") int limit) {
        requireFirm();
        return orderService.list(status, limit);
    }

    @GetMapping("/{orderNo}")
    public OrderResponse get(@PathVariable String orderNo) {
        requireFirm();
        return orderService.get(orderNo);
    }

    private void requireFirm() {
        String schema = TenantContext.getCurrentTenant();
        if (schema == null || schema.isBlank() || "public".equals(schema)) {
            throw new AgentApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Boutique orders require a firm login", null);
        }
        schemaInitializer.ensureSchema(schema);
    }
}

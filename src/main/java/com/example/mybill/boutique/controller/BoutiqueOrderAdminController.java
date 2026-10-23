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

import jakarta.servlet.http.HttpServletRequest;

import java.util.List;
import java.util.Map;

/**
 * Read-only boutique order screens in mybill-ui ("Boutique Orders"). Uses the normal app login (TenantFilter JWT),
 * so each firm sees its own orders - unlike /api/agent/**, which is Google-token-only and fixed to the agent's firm.
 */
@RestController
@RequestMapping("/api/boutique-orders")
public class BoutiqueOrderAdminController {

    @Autowired private AgentOrderService orderService;
    @Autowired private BoutiqueSchemaInitializer schemaInitializer;
    @Autowired private com.example.mybill.multitenancy.JwtUtil jwtUtil;

    /** e.g. ?status=DRAFT&limit=50 (limit 1..100, newest first), or ?measurement=pending (oldest payment first). */
    @GetMapping
    public List<OrderSummary> list(@RequestParam(required = false) String status,
                                   @RequestParam(required = false) String measurement,
                                   @RequestParam(required = false) String dispatch,
                                   @RequestParam(defaultValue = "50") int limit) {
        requireFirm();
        if ("pending".equalsIgnoreCase(measurement)) return orderService.pendingMeasurementCalls(limit);
        if ("pending".equalsIgnoreCase(dispatch)) return orderService.pendingDispatch(limit);
        return orderService.list(status, limit);
    }

    /** Sidebar badge: paid orders waiting for the tailor's measurement call. */
    @GetMapping("/count")
    public Map<String, Long> count(@RequestParam(defaultValue = "pending") String measurement) {
        requireFirm();
        return Map.of("measurement_pending", orderService.countPendingMeasurementCalls(),
            "dispatch_pending", orderService.countPendingDispatch());
    }

    /** Packed and sent (paid fabric / readymade orders, or a stitched order that is ready). */
    @PostMapping("/{orderNo}/dispatched")
    public OrderResponse dispatched(@PathVariable String orderNo, HttpServletRequest request) {
        requireFirm();
        return orderService.markDispatched(orderNo, staffName(request));
    }

    @PostMapping("/{orderNo}/measurements-taken")
    public OrderResponse measurementsTaken(@PathVariable String orderNo, HttpServletRequest request) {
        requireFirm();
        return orderService.markMeasurementsTaken(orderNo, staffName(request));
    }

    private String staffName(HttpServletRequest request) {
        try {
            return jwtUtil.extractClaims(request.getHeader("Authorization").substring("Bearer ".length())).getSubject();
        } catch (Exception e) {
            return null;
        }
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

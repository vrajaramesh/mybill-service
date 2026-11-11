package com.example.mybill.boutique.controller;

import com.example.mybill.boutique.config.BoutiqueSchemaInitializer;
import com.example.mybill.boutique.exception.AgentApiException;
import com.example.mybill.boutique.service.OwnerNumberService;
import com.example.mybill.multitenancy.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** mybill-ui: the WhatsApp numbers whose forwarded supplier posts become dropship collections. */
@RestController
@RequestMapping("/api/dropship-owner-numbers")
public class OwnerNumberAdminController {

    @Autowired private OwnerNumberService numbers;
    @Autowired private BoutiqueSchemaInitializer schemaInitializer;

    @GetMapping
    public List<OwnerNumberService.OwnerNumber> list() {
        requireFirm();
        return numbers.list();
    }

    /** Add or update: {phone, label, active}. */
    @PostMapping
    public OwnerNumberService.OwnerNumber save(@RequestBody Map<String, Object> body) {
        requireFirm();
        return numbers.save(body);
    }

    @DeleteMapping("/{phone}")
    public Map<String, Object> delete(@PathVariable String phone) {
        requireFirm();
        numbers.delete(phone);
        return Map.of("deleted", true);
    }

    private void requireFirm() {
        String schema = TenantContext.getCurrentTenant();
        if (schema == null || schema.isBlank() || "public".equals(schema)) {
            throw new AgentApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Owner numbers require a firm login", null);
        }
        schemaInitializer.ensureSchema(schema);
    }
}

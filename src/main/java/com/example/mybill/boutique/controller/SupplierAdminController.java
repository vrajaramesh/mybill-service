package com.example.mybill.boutique.controller;

import com.example.mybill.boutique.config.BoutiqueSchemaInitializer;
import com.example.mybill.boutique.exception.AgentApiException;
import com.example.mybill.boutique.service.DropshipService;
import com.example.mybill.multitenancy.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** mybill-ui "Boutique → Collections → Suppliers": the dropship suppliers (who stock and ship our DROPSHIP collections). */
@RestController
@RequestMapping("/api/boutique-suppliers")
public class SupplierAdminController {

    @Autowired private DropshipService dropship;
    @Autowired private BoutiqueSchemaInitializer schemaInitializer;

    @GetMapping
    public List<DropshipService.Supplier> list() {
        requireFirm();
        return dropship.suppliers();
    }

    @PostMapping
    public ResponseEntity<DropshipService.Supplier> create(@RequestBody Map<String, Object> body) {
        requireFirm();
        return ResponseEntity.status(HttpStatus.CREATED).body(dropship.saveSupplier(null, body));
    }

    @PutMapping("/{id}")
    public DropshipService.Supplier update(@PathVariable int id, @RequestBody Map<String, Object> body) {
        requireFirm();
        return dropship.saveSupplier(id, body);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable int id) {
        requireFirm();
        dropship.deleteSupplier(id);
        return Map.of("deleted", true);
    }

    private void requireFirm() {
        String schema = TenantContext.getCurrentTenant();
        if (schema == null || schema.isBlank() || "public".equals(schema)) {
            throw new AgentApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Suppliers require a firm login", null);
        }
        schemaInitializer.ensureSchema(schema);
    }
}

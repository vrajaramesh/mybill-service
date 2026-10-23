package com.example.mybill.boutique.controller;

import com.example.mybill.boutique.config.BoutiqueSchemaInitializer;
import com.example.mybill.boutique.exception.AgentApiException;
import com.example.mybill.boutique.service.CollectionService;
import com.example.mybill.multitenancy.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** mybill-ui "Boutique → Collections": fabric / readymade collections (one Instagram post each) and their items. */
@RestController
@RequestMapping("/api/boutique-collections")
public class CollectionAdminController {

    @Autowired private CollectionService collections;
    @Autowired private BoutiqueSchemaInitializer schemaInitializer;

    @GetMapping
    public List<Map<String, Object>> list() {
        requireFirm();
        return collections.list();
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable int id) {
        requireFirm();
        return collections.get(String.valueOf(id));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        requireFirm();
        return ResponseEntity.status(HttpStatus.CREATED).body(collections.save(null, body));
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable int id, @RequestBody Map<String, Object> body) {
        requireFirm();
        return collections.save(id, body);
    }

    /** Body {"items": [{item_code, product_id, colour, image_url}]} in display order. */
    @PutMapping("/{id}/items")
    public Map<String, Object> items(@PathVariable int id, @RequestBody Map<String, Object> body) {
        requireFirm();
        List<Map<String, Object>> items = new ArrayList<>();
        if (body.get("items") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    m.forEach((k, v) -> item.put(String.valueOf(k), v));
                    items.add(item);
                }
            }
        }
        return collections.replaceItems(id, items);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable int id) {
        requireFirm();
        collections.delete(id);
        return Map.of("deleted", true);
    }

    private void requireFirm() {
        String schema = TenantContext.getCurrentTenant();
        if (schema == null || schema.isBlank() || "public".equals(schema)) {
            throw new AgentApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Collections require a firm login", null);
        }
        schemaInitializer.ensureSchema(schema);
    }
}

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

/** mybill-ui "Boutique → Collections": fabric / readymade / dropship / wholesale collections (one Instagram post each) and their items. */
@RestController
@RequestMapping("/api/boutique-collections")
public class CollectionAdminController {

    @Autowired private CollectionService collections;
    @Autowired private BoutiqueSchemaInitializer schemaInitializer;
    @Autowired private com.example.mybill.wholesale.config.WholesaleSchemaInitializer wholesaleSchema;
    @Autowired private com.example.mybill.boutique.service.WholesaleCollectionService wholesaleCollections;

    /** Active Wholesale-module products for the items of a WHOLESALE collection. */
    @GetMapping("/wholesale-products")
    public List<Map<String, Object>> wholesaleProducts() {
        requireFirm();
        return wholesaleCollections.pickList();
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        requireFirm();
        return collections.list();
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable int id) {
        requireFirm();
        return collections.get(String.valueOf(id), true);
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

    @Autowired private com.example.mybill.boutique.service.DropshipApprovalService approval;
    @Autowired private com.example.mybill.multitenancy.JwtUtil jwtUtil;

    /** Dropship: ACTIVE (customers can buy it) - same as the owner's WhatsApp Approve. */
    @PostMapping("/{id}/approve")
    public Map<String, Object> approve(@PathVariable int id, @RequestHeader(value = "Authorization", required = false) String auth) {
        requireFirm();
        return approval.approve(id, user(auth));
    }

    @PostMapping("/{id}/archive")
    public Map<String, Object> archive(@PathVariable int id, @RequestHeader(value = "Authorization", required = false) String auth) {
        requireFirm();
        return approval.archive(id, user(auth));
    }

    /** One dropship variant: {status: AVAILABLE|SOLD_OUT, price}. */
    @PatchMapping("/{id}/variants/{code}")
    public Map<String, Object> variant(@PathVariable int id, @PathVariable String code, @RequestBody Map<String, Object> body) {
        requireFirm();
        return approval.updateVariant(id, code, body);
    }

    /** Same selling price for every variant: {price}. */
    @PostMapping("/{id}/price")
    public Map<String, Object> priceForAll(@PathVariable int id, @RequestBody Map<String, Object> body) {
        requireFirm();
        return approval.setPriceForAll(id, body.get("price"));
    }

    private String user(String auth) {
        try {
            return auth != null && auth.startsWith("Bearer ") ? jwtUtil.extractClaims(auth.substring(7)).getSubject() : null;
        } catch (Exception e) {
            return null;
        }
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
        wholesaleSchema.ensureSchema(schema);   // WHOLESALE collections read the Wholesale module's products
    }
}

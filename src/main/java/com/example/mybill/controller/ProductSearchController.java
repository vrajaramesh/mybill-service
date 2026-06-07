package com.example.mybill.controller;

import com.example.mybill.multitenancy.TenantContext;
import com.example.mybill.service.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/products/search")
public class ProductSearchController {

    @Autowired private ProductVectorService vectorService;
    @Autowired private ImageSearchService imageSearchService;

    @PostMapping
    public ResponseEntity<?> search(@RequestBody ProductSearchRequest request) {
        try {
            List<ProductSearchResult> results;

            if (request.getImageBase64() != null && !request.getImageBase64().isBlank()) {
                results = vectorService.searchByImage(
                    request.getImageBase64(),
                    request.getMimeType() != null ? request.getMimeType() : "image/jpeg"
                );
            } else if (request.getQuery() != null && !request.getQuery().isBlank()) {
                results = vectorService.searchByText(request.getQuery(), request.getLimit());
            } else {
                return ResponseEntity.badRequest()
                    .body(Map.of("error", "Provide either 'query' or 'imageBase64'"));
            }

            return ResponseEntity.ok(results);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError()
                .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Search generated product images by natural language query.
     * e.g. "yellow kurti wedding", "blue saree casual", "red blouse"
     * GET /api/products/image-search?q=yellow+saree&limit=10
     */
    @GetMapping("/images")
    public ResponseEntity<?> imageSearch(
            @RequestParam String q,
            @RequestParam(defaultValue = "12") int limit) {
        try {
            String schema = TenantContext.getCurrentTenant();
            if (schema == null) return ResponseEntity.badRequest()
                .body(Map.of("error", "No tenant context"));
            List<ImageSearchResult> results = imageSearchService.search(q, schema, limit);
            return ResponseEntity.ok(results);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}

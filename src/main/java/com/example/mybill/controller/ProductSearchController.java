package com.example.mybill.controller;

import com.example.mybill.service.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/products/search")
public class ProductSearchController {

    @Autowired private ProductVectorService vectorService;

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
                    .body(java.util.Map.of("error", "Provide either 'query' or 'imageBase64'"));
            }

            return ResponseEntity.ok(results);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError()
                .body(java.util.Map.of("error", e.getMessage()));
        }
    }
}

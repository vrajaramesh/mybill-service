package com.example.mybill.controller;

import com.example.mybill.dto.Firm;
import com.example.mybill.multitenancy.TenantContext;
import com.example.mybill.repository.FirmRepository;
import com.example.mybill.service.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/ai-config")
public class AiConfigController {

    @Autowired private AiGenerationPromptRepository promptRepo;
    @Autowired private ProductModelConfigRepository modelRepo;
    @Autowired private FirmRepository firmRepo;
    @Autowired private ProductRepository productRepo;
    @Autowired private ProductImageRepository productImageRepo;

    // ── Generation Prompts ────────────────────────────────────────────────────

    @GetMapping("/prompts")
    public List<AiGenerationPrompt> listPrompts() {
        return promptRepo.findAll();
    }

    @PostMapping("/prompts")
    public ResponseEntity<?> createPrompt(@RequestBody AiGenerationPrompt body) {
        body.setId(null);
        body.setCategory(blankToNull(body.getCategory()));
        body.setCreatedAt(LocalDateTime.now());
        body.setUpdatedAt(LocalDateTime.now());
        return ResponseEntity.ok(promptRepo.save(body));
    }

    @PutMapping("/prompts/{id}")
    public ResponseEntity<?> updatePrompt(@PathVariable Long id, @RequestBody AiGenerationPrompt body) {
        return promptRepo.findById(id).map(existing -> {
            existing.setCategory(blankToNull(body.getCategory()));
            existing.setSuitableFor(body.getSuitableFor());
            existing.setPrompt(body.getPrompt());
            existing.setUpdatedAt(LocalDateTime.now());
            return ResponseEntity.ok(promptRepo.save(existing));
        }).orElse(ResponseEntity.notFound().build());
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    @DeleteMapping("/prompts/{id}")
    public ResponseEntity<?> deletePrompt(@PathVariable Long id) {
        if (!promptRepo.existsById(id)) return ResponseEntity.notFound().build();
        promptRepo.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // ── Product Models ────────────────────────────────────────────────────────

    @GetMapping("/models")
    public List<ProductModelConfig> listModels() {
        return modelRepo.findAll();
    }

    @PostMapping("/models")
    public ResponseEntity<?> createModel(@RequestBody ProductModelConfig body) {
        body.setId(null);
        body.setCreatedAt(LocalDateTime.now());
        body.setUpdatedAt(LocalDateTime.now());
        return ResponseEntity.ok(modelRepo.save(body));
    }

    @PutMapping("/models/{id}")
    public ResponseEntity<?> updateModel(@PathVariable Long id, @RequestBody ProductModelConfig body) {
        return modelRepo.findById(id).map(existing -> {
            existing.setGarmentType(body.getGarmentType());
            existing.setModelName(body.getModelName());
            existing.setModelImageUrl(body.getModelImageUrl());
            existing.setUpdatedAt(LocalDateTime.now());
            return ResponseEntity.ok(modelRepo.save(existing));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/models/{id}")
    public ResponseEntity<?> deleteModel(@PathVariable Long id) {
        if (!modelRepo.existsById(id)) return ResponseEntity.notFound().build();
        modelRepo.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // ── Cross-Firm: list products from another firm ───────────────────────────

    @GetMapping("/firm-products")
    public ResponseEntity<?> listFirmProducts(@RequestParam String firmCode) {
        String currentSchema = TenantContext.getCurrentTenant();
        Optional<Firm> firmOpt = firmRepo.findByFirmCode(firmCode);
        if (firmOpt.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "Firm not found: " + firmCode));

        List<Product> products;
        try {
            TenantContext.setCurrentTenant(firmOpt.get().getSchemaName());
            products = productRepo.findAll();
        } finally {
            TenantContext.setCurrentTenant(currentSchema);
        }
        return ResponseEntity.ok(products);
    }

    // ── Cross-Firm Image Copy ─────────────────────────────────────────────────
    // Copies product image URLs from sourceFirmCode/sourceProductId into
    // the current tenant's targetProductId (no file duplication — same Cloudinary URLs).

    @PostMapping("/copy-firm-images")
    public ResponseEntity<?> copyFirmImages(@RequestBody Map<String, Object> body) {
        String sourceFirmCode   = (String) body.get("sourceFirmCode");
        Integer sourceProductId = (Integer) body.get("sourceProductId");
        Integer targetProductId = (Integer) body.get("targetProductId");

        if (sourceFirmCode == null || sourceProductId == null || targetProductId == null) {
            return ResponseEntity.badRequest()
                .body(Map.of("error", "sourceFirmCode, sourceProductId, targetProductId are required"));
        }

        // Remember current (target) schema
        String targetSchema = TenantContext.getCurrentTenant();

        // Look up source firm schema
        Optional<Firm> sourceFirmOpt = firmRepo.findByFirmCode(sourceFirmCode);
        if (sourceFirmOpt.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Source firm not found: " + sourceFirmCode));
        }
        String sourceSchema = sourceFirmOpt.get().getSchemaName();

        // Fetch images from source schema
        List<String> sourceUrls;
        try {
            TenantContext.setCurrentTenant(sourceSchema);
            sourceUrls = productImageRepo.findByProductProductIdOrderByCreatedAtAsc(sourceProductId)
                .stream().map(ProductImage::getImageUrl).toList();
        } finally {
            TenantContext.setCurrentTenant(targetSchema);
        }

        if (sourceUrls.isEmpty()) {
            return ResponseEntity.ok(Map.of("copied", 0, "message", "Source product has no images"));
        }

        // Verify target product exists in current schema
        Optional<Product> targetProductOpt = productRepo.findById(targetProductId);
        if (targetProductOpt.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Target product not found"));
        }
        Product targetProduct = targetProductOpt.get();

        // Create image records in target schema pointing to same URLs
        int count = 0;
        for (String url : sourceUrls) {
            ProductImage img = new ProductImage();
            img.setProduct(targetProduct);
            img.setImageUrl(url);
            img.setPublicId("copied-from-" + sourceFirmCode);
            img.setImageType("copied");
            img.setMediaType("image");
            img.setCreatedAt(LocalDateTime.now());
            productImageRepo.save(img);
            count++;
        }

        return ResponseEntity.ok(Map.of("copied", count, "message", count + " images copied from " + sourceFirmCode));
    }
}

package com.example.mybill.controller;

import com.example.mybill.service.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api/products/{productId}/ai-photos")
public class AIPhotoController {

    @Autowired private ProductService productService;
    @Autowired private OpenAIService openAIService;
    @Autowired private GeminiService geminiService;
    @Autowired private ProductPhotoPromptBuilder promptBuilder;
    @Autowired private ImageUploadService imageUploadService;
    @Autowired private ProductImageRepository productImageRepository;

    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    @PostMapping("/generate")
    public ResponseEntity<?> generatePhotos(
            @PathVariable Integer productId,
            @RequestBody AIPhotoRequest request) {

        Optional<Product> productOpt = productService.getProductById(productId);
        if (productOpt.isEmpty()) return ResponseEntity.notFound().build();

        Product product = productOpt.get();
        String category = product.getCategory() != null
                          ? product.getCategory().getCategoryName() : "";

        try {
            System.err.println("[AI-PHOTO] Starting generation for product " + productId + " / category: " + category);

            // Step 1: analyze reference image with Gemini Vision (free tier)
            String description = geminiService.analyzeProductImage(
                request.getImageBase64(),
                request.getMimeType() != null ? request.getMimeType() : "image/jpeg",
                product.getProductName(),
                category
            );
            System.err.println("[AI-PHOTO] Gemini description: " + description);

            // Step 2: build 4 category-aware prompts (uses suitableFor if set)
            List<String> prompts = promptBuilder.buildPrompts(
                description, category, product.getProductName(), product.getSuitableFor());

            // Step 3: generate 4 DALL-E images in parallel, then upload each to Cloudinary
            List<CompletableFuture<String>> futures = prompts.stream()
                .map(prompt -> CompletableFuture.supplyAsync(() -> {
                    System.err.println("[AI-PHOTO] Generating DALL-E image...");
                    String dalleUrl = openAIService.generateDalleImage(prompt);
                    System.err.println("[AI-PHOTO] Uploading image: " + dalleUrl.substring(0, Math.min(60, dalleUrl.length())));
                    return imageUploadService.uploadFromUrl(dalleUrl);
                }, executor))
                .toList();

            List<String> urls = new ArrayList<>();
            for (CompletableFuture<String> f : futures) {
                try {
                    urls.add(f.get());
                } catch (Exception e) {
                    System.err.println("[AI-PHOTO] One image failed: " + e.getMessage());
                }
            }

            System.err.println("[AI-PHOTO] Done. " + urls.size() + " images ready.");
            return ResponseEntity.ok(urls);

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError()
                .body(java.util.Map.of("error", e.getMessage() != null ? e.getMessage() : e.getClass().getName()));
        }
    }

    @PostMapping("/save")
    public ResponseEntity<?> savePhotos(
            @PathVariable Integer productId,
            @RequestBody List<String> imageUrls) {

        Optional<Product> productOpt = productService.getProductById(productId);
        if (productOpt.isEmpty()) return ResponseEntity.notFound().build();

        Product product = productOpt.get();
        List<ProductImage> saved = new ArrayList<>();

        for (String url : imageUrls) {
            if (url == null || url.isBlank()) continue;
            ProductImage img = new ProductImage();
            img.setProduct(product);
            img.setImageUrl(url);
            img.setPublicId("ai-generated");
            img.setImageType("ai-generated");
            img.setCreatedAt(LocalDateTime.now());
            saved.add(productImageRepository.save(img));
        }

        return ResponseEntity.ok(saved);
    }
}

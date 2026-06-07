package com.example.mybill.controller;

import com.example.mybill.multitenancy.TenantContext;
import com.example.mybill.service.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api/products/{productId}/ai-photos")
public class AIPhotoController {

    // Appended to every prompt — fabric must not change colour or design.
    private static final String FABRIC_FIDELITY =
        " CRITICAL: absolutely no colour change, no design change, no pattern change to the fabric." +
        " Reproduce the fabric's exact colour, print size, motif, and weave with 100% accuracy." +
        " Shot in soft natural daylight, 6500K cool neutral colour temperature." +
        " Absolutely no warm, orange, yellow, or amber tones." +
        " Sharp, crisp, photorealistic, high-resolution. Not AI-generated looking.";

    // Appended to garment/fashion prompts only (not flat-lay).
    private static final String MODEL_SCENE =
        " The model is a natural, good-looking Indian woman with a warm confident smile and a traditional bindi." +
        " Outdoor traditional event setting with lush green trees and natural garden greenery in the background." +
        " Professional Indian fashion editorial photography on Sony A7R V.";

    @Autowired private ProductService productService;
    @Autowired private ImageGenerationProvider imageProvider;
    @Autowired private ProductImageRepository productImageRepository;
    @Autowired private AiGenerationPromptRepository aiPromptRepo;
    @Autowired private ImageEmbeddingService imageEmbeddingService;

    // Generation runs on this pool — request thread returns 202 immediately.
    private final ExecutorService generationExecutor = Executors.newFixedThreadPool(4);

    // ── Generate (returns 202 immediately, runs generation in background) ────

    @PostMapping("/generate")
    public ResponseEntity<?> generatePhotos(
            @PathVariable Integer productId,
            @RequestBody AIPhotoRequest request) {

        Optional<Product> productOpt = productService.getProductById(productId);
        if (productOpt.isEmpty()) return ResponseEntity.notFound().build();

        Product product = productOpt.get();
        String category = product.getCategory() != null ? product.getCategory().getCategoryName() : "";
        String schema   = TenantContext.getCurrentTenant();

        String fabricDesc = buildFabricDesc(product, category);

        // Decode base64 directly to bytes — no Cloudinary upload, no DB save for the reference.
        byte[] imageBytes    = null;
        String imageMimeType = null;
        if (request.getImageBase64() != null && !request.getImageBase64().isBlank()) {
            try {
                imageMimeType = request.getMimeType() != null ? request.getMimeType() : "image/jpeg";
                imageBytes = java.util.Base64.getDecoder().decode(request.getImageBase64());
                System.err.println("[AI-PHOTO] Reference image decoded from upload: "
                    + imageBytes.length + " bytes ("
                    + String.format("%.1f", imageBytes.length / 1024.0) + " KB), type=" + imageMimeType);
            } catch (Exception e) {
                System.err.println("[AI-PHOTO] Failed to decode reference image: " + e.getMessage());
            }
        }

        // Fall back to first existing non-AI product image URL only if no upload was provided.
        String referenceUrl = null;
        if (imageBytes == null) {
            referenceUrl = productImageRepository
                .findByProductProductIdOrderByCreatedAtAsc(productId).stream()
                .filter(img -> !"ai-generated".equals(img.getImageType()))
                .map(ProductImage::getImageUrl)
                .findFirst().orElse(null);
            System.err.println("[AI-PHOTO] No upload — using existing image URL: " + referenceUrl);
        }

        List<String> garmentTypes = parseSuitableFor(product.getSuitableFor());
        List<String> occasions    = parseOccasions(product.getTags());

        final byte[] imageBytesF    = imageBytes;
        final String imageMimeTypeF = imageMimeType;
        final String refUrlFinal    = referenceUrl;
        System.err.println("[AI-PHOTO] Queued async generation for product=" + productId
            + " category=" + category + " hasUpload=" + (imageBytes != null)
            + " referenceUrl=" + refUrlFinal);

        // Fire and forget — caller gets 202 immediately
        CompletableFuture.runAsync(
            () -> runGeneration(productId, fabricDesc, imageBytesF, imageMimeTypeF, refUrlFinal,
                                garmentTypes, occasions, category, schema),
            generationExecutor
        );

        return ResponseEntity.accepted()
            .body(Map.of("message",
                "AI photo generation started. Images will appear in the product gallery automatically."));
    }

    // ── Background generation + auto-save ─────────────────────────────────────

    private void runGeneration(int productId, String fabricDesc,
                                byte[] imageBytes, String imageMimeType, String referenceUrl,
                                List<String> garmentTypes, List<String> occasions,
                                String category, String schema) {
        TenantContext.setCurrentTenant(schema);
        try {
            // Re-fetch product in this thread so entity is managed
            Product product = productService.getProductById(productId).orElse(null);
            if (product == null) {
                System.err.println("[AI-PHOTO] Product " + productId + " not found in async thread");
                return;
            }

            List<PhotoResult> allPhotos = new ArrayList<>();

            // ── Photo 0: flat-lay (only if "Fabric" DB prompt exists) ──────────
            PhotoResult flatLay = generateFlatLayPhoto(product, imageBytes, imageMimeType, referenceUrl);
            if (flatLay != null) {
                savePhoto(product, flatLay);
                allPhotos.add(flatLay);
            }

            // ── Photos 1+: garment photos (DB prompts only) ───────────────────
            List<PhotoResult> fashion = garmentTypes.isEmpty()
                ? List.of()
                : dbDrivenGeneration(category, garmentTypes, occasions,
                                     imageBytes, imageMimeType, referenceUrl);

            if (fashion.isEmpty() && !garmentTypes.isEmpty()) {
                System.err.println("[AI-PHOTO] No DB prompts matched for garments=" + garmentTypes
                    + " category=" + category + " — skipping fashion photos. Add prompts via AI Config.");
            }

            for (PhotoResult p : fashion) {
                savePhoto(product, p);
                allPhotos.add(p);
            }

            // ── Async embed ────────────────────────────────────────────────────
            if (!allPhotos.isEmpty()) {
                imageEmbeddingService.embedAllAsync(productId, allPhotos, schema);
            }

            System.err.println("[AI-PHOTO] Done. " + allPhotos.size() + " images saved for product=" + productId);

        } catch (Exception e) {
            System.err.println("[AI-PHOTO] Generation failed for product=" + productId + ": " + e.getMessage());
            e.printStackTrace();
        } finally {
            TenantContext.clear();
        }
    }

    private void savePhoto(Product product, PhotoResult photo) {
        try {
            ProductImage img = new ProductImage();
            img.setProduct(product);
            img.setImageUrl(photo.url());
            img.setPublicId("ai-generated");
            img.setImageType("ai-generated");
            img.setCreatedAt(LocalDateTime.now());
            productImageRepository.save(img);
        } catch (Exception e) {
            System.err.println("[AI-PHOTO] Failed to save photo: " + e.getMessage());
        }
    }

    // ── Photo 0: flat-lay — skip if no "Fabric" DB prompt ────────────────────

    private PhotoResult generateFlatLayPhoto(Product product,
                                              byte[] imageBytes, String imageMimeType,
                                              String referenceUrl) {
        try {
            String category = product.getCategory() != null ? product.getCategory().getCategoryName() : "";
            List<AiGenerationPrompt> fabricDbPrompts = aiPromptRepo.findBySuitableForAndCategory("Fabric", category);
            if (fabricDbPrompts.isEmpty()) {
                System.err.println("[AI-PHOTO] No 'Fabric' DB prompt found — skipping flat-lay. Add one via AI Config.");
                return null;
            }
            String prompt = "Using the exact fabric shown in the reference image, "
                + fabricDbPrompts.get(0).getPrompt() + FABRIC_FIDELITY;
            System.err.println("[AI-PHOTO] Flat-lay prompt: " + prompt);
            String url = imageBytes != null
                ? imageProvider.generateFlatLayFromBytes(imageBytes, imageMimeType, prompt)
                : imageProvider.generateFlatLay(referenceUrl, prompt);
            System.err.println("[AI-PHOTO] Flat-lay generated: " + url);
            return new PhotoResult(url, "Fabric", "product-shot", "");
        } catch (Exception e) {
            System.err.println("[AI-PHOTO] Flat-lay failed: " + e.getMessage());
            return null;
        }
    }

    // ── DB-driven generation (garment × occasion) ─────────────────────────────

    private List<PhotoResult> dbDrivenGeneration(String category,
                                                  List<String> garmentTypes, List<String> occasions,
                                                  byte[] imageBytes, String imageMimeType,
                                                  String referenceUrl) {
        List<String[]> combos = buildCombos(garmentTypes, occasions, 6);
        List<CompletableFuture<PhotoResult>> futures = new ArrayList<>();
        boolean anyFound = false;

        for (String[] combo : combos) {
            String garment = combo[0];
            String occasion = combo[1];

            List<AiGenerationPrompt> dbPrompts = aiPromptRepo.findBySuitableForAndCategory(garment, category);
            if (dbPrompts.isEmpty()) continue;
            anyFound = true;

            final String g = garment, o = occasion, base = dbPrompts.get(0).getPrompt();

            futures.add(CompletableFuture.supplyAsync(() -> {
                System.err.println("[AI-PHOTO] Generating garment=" + g + " occasion=" + o);
                String prompt = buildGarmentPrompt(g, o, base) + MODEL_SCENE + FABRIC_FIDELITY;
                System.err.println("[AI-PHOTO] Garment prompt [" + g + "/" + o + "]: " + prompt);
                String url = imageBytes != null
                    ? imageProvider.generateGarmentFromBytes(imageBytes, imageMimeType, prompt)
                    : imageProvider.generateGarment(referenceUrl, prompt);
                return new PhotoResult(url, g, o != null ? o : "general", "");
            }, generationExecutor));
        }

        if (!anyFound) return List.of();

        List<PhotoResult> results = new ArrayList<>();
        for (CompletableFuture<PhotoResult> f : futures) {
            try { results.add(f.get()); }
            catch (Exception e) { System.err.println("[AI-PHOTO] Garment photo failed: " + e.getMessage()); }
        }
        return results;
    }

    // ── Prompt helpers ────────────────────────────────────────────────────────

    private String buildGarmentPrompt(String garment, String occasion, String base) {
        String lower = garment != null ? garment.toLowerCase() : "";
        String modelCtx = (lower.contains("frock") || lower.contains("kids"))
            ? "5-year-old cute Indian girl wearing the fabric from the reference image as a " + garment
              + ". Cheerful expression, playful natural pose. "
            : "Using the exact fabric shown in the reference image, ";
        String prompt = modelCtx + base;
        if (occasion != null) prompt += " Occasion: " + occasion + ".";
        return prompt;
    }

    private String buildFabricDesc(Product product, String category) {
        StringBuilder sb = new StringBuilder();
        if (category != null && !category.isBlank())      sb.append(category).append(" fabric. ");
        if (product.getProductName() != null)              sb.append("Product: ").append(product.getProductName()).append(". ");
        if (product.getDescription() != null && !product.getDescription().isBlank())
                                                           sb.append(product.getDescription()).append(". ");
        if (product.getSuitableFor() != null && !product.getSuitableFor().isBlank())
                                                           sb.append("Suitable for: ").append(product.getSuitableFor()).append(". ");
        if (product.getTags() != null && !product.getTags().isBlank())
                                                           sb.append("Occasions: ").append(product.getTags()).append(".");
        return sb.toString().trim();
    }

    private List<String[]> buildCombos(List<String> garments, List<String> occasions, int max) {
        List<String[]> combos = new ArrayList<>();
        for (String garment : garments) {
            if (occasions.isEmpty()) {
                combos.add(new String[]{garment, null});
            } else {
                for (String occasion : occasions) {
                    combos.add(new String[]{garment, occasion});
                    if (combos.size() >= max) return combos;
                }
            }
            if (combos.size() >= max) break;
        }
        return combos;
    }

    private List<String> parseSuitableFor(String s) {
        if (s == null || s.isBlank()) return List.of();
        return Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
    }

    private List<String> parseOccasions(String s) {
        if (s == null || s.isBlank()) return List.of();
        return Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
    }

    // ── Manual save (kept for any future manual override use) ─────────────────

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

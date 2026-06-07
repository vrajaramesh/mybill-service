package com.example.mybill.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    @Autowired private ProductService productService;
    @Autowired private ProductCategoryService productCategoryService;
    @Autowired private ProductCategoryRepository productCategoryRepository;
    @Autowired private ProductSubCategoryRepository subCategoryRepository;
    @Autowired private ProductImageRepository productImageRepository;
    @Autowired private ProductVectorService productVectorService;
    @Autowired private ImageEmbeddingService imageEmbeddingService;
    @Autowired private GeminiService geminiService;
    @Autowired private GroqChatService groqChatService;

    // ── AI Description (Groq llama-3.2-11b-vision-preview) ───────────────

    @PostMapping("/generate-description")
    public ResponseEntity<?> generateDescription(@RequestBody Map<String, String> req) {
        try {
            String description = groqChatService.generateFabricDescription(
                req.get("productName"),
                req.get("category"),
                req.get("suitableFor"),
                req.get("tags"),
                req.get("imageUrl")
            );
            return ResponseEntity.ok(Map.of("description", description));
        } catch (Exception e) {
            System.err.println("[DESC] Generation failed: " + e.getMessage());
            return ResponseEntity.internalServerError()
                .body(Map.of("error", e.getMessage()));
        }
    }

    // ── Products ──────────────────────────────────────────────────────────

    @GetMapping
    public List<Product> getAllProducts() {
        return productService.getAllProducts();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Product> getProductById(@PathVariable Integer id) {
        return productService.getProductById(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public Product createProduct(@RequestBody Product product) {
        Product saved = productService.createProduct(product);
        productVectorService.triggerEmbeddingAsync(saved.getProductId(), null);
        return saved;
    }

    @PutMapping("/{id}")
    public ResponseEntity<Product> updateProduct(@PathVariable Integer id, @RequestBody Product productDetails) {
        Product updated = productService.updateProduct(id, productDetails);
        if (updated != null) productVectorService.triggerEmbeddingAsync(updated.getProductId(), null);
        return updated != null ? ResponseEntity.ok(updated) : ResponseEntity.notFound().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteProduct(@PathVariable Integer id) {
        productService.deleteProduct(id);
        return ResponseEntity.noContent().build();
    }

    // ── Product Images ────────────────────────────────────────────────────

    @GetMapping("/{id}/images")
    public ResponseEntity<List<ProductImage>> getProductImages(@PathVariable Integer id) {
        return ResponseEntity.ok(productImageRepository.findByProductProductIdOrderBySortOrderAscCreatedAtAsc(id));
    }

    @PutMapping("/{id}/images/reorder")
    public ResponseEntity<Void> reorderProductImages(
            @PathVariable Integer id, @RequestBody List<Map<String, Integer>> order) {
        for (Map<String, Integer> item : order) {
            Integer imageId = item.get("imageId");
            Integer sortOrder = item.get("sortOrder");
            if (imageId != null && sortOrder != null) {
                productImageRepository.findById(imageId).ifPresent(img -> {
                    img.setSortOrder(sortOrder);
                    productImageRepository.save(img);
                });
            }
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/images")
    public ResponseEntity<ProductImage> addProductImage(
            @PathVariable Integer id, @RequestBody Map<String, String> body) {
        Optional<Product> product = productService.getProductById(id);
        if (product.isEmpty()) return ResponseEntity.notFound().build();
        ProductImage img = new ProductImage();
        img.setProduct(product.get());
        img.setImageUrl(body.get("imageUrl"));
        img.setPublicId(body.get("publicId"));
        img.setImageType(body.getOrDefault("imageType", "user"));
        img.setMediaType(body.getOrDefault("mediaType", "image"));
        img.setCreatedAt(LocalDateTime.now());
        ProductImage saved = productImageRepository.save(img);
        String imageUrl = body.get("imageUrl");
        productVectorService.triggerEmbeddingAsync(id, imageUrl);
        // Only classify+embed images (not videos) in product_image_embeddings
        if ("image".equals(img.getMediaType()) && imageUrl != null && !imageUrl.isBlank()) {
            String schema = com.example.mybill.multitenancy.TenantContext.getCurrentTenant();
            if (schema != null) {
                imageEmbeddingService.embedUploadedImageAsync(id, imageUrl, schema);
            }
        }
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/{id}/images/{imageId}")
    public ResponseEntity<Void> deleteProductImage(
            @PathVariable Integer id, @PathVariable Integer imageId) {
        productImageRepository.deleteById(imageId);
        return ResponseEntity.noContent().build();
    }

    // ── Categories ────────────────────────────────────────────────────────

    /** All categories for admin UI (includes offline ones). */
    @GetMapping("/categories")
    public List<ProductCategory> getProductCategories() {
        return productCategoryService.getAllCategories();
    }

    @PostMapping("/categories")
    public ResponseEntity<ProductCategory> createCategory(@RequestBody ProductCategory category) {
        if (productCategoryRepository.existsById(category.getCategoryName())) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(productCategoryRepository.save(category));
    }

    @PutMapping("/categories/{name}")
    public ResponseEntity<ProductCategory> updateCategory(
            @PathVariable String name, @RequestBody ProductCategory body) {
        return productCategoryRepository.findById(name).map(cat -> {
            cat.setIsOnline(body.getIsOnline());
            return ResponseEntity.ok(productCategoryRepository.save(cat));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/categories/{name}")
    public ResponseEntity<Void> deleteCategory(@PathVariable String name) {
        if (!productCategoryRepository.existsById(name)) return ResponseEntity.notFound().build();
        productCategoryRepository.deleteById(name);
        return ResponseEntity.noContent().build();
    }

    // ── Sub-categories ────────────────────────────────────────────────────

    /** All sub-categories for a given category. */
    @GetMapping("/categories/{categoryName}/subcategories")
    public List<ProductSubCategory> getSubCategories(@PathVariable String categoryName) {
        return subCategoryRepository.findByCategoryCategoryName(categoryName);
    }

    /** All sub-categories (for product form dropdown). */
    @GetMapping("/subcategories")
    public List<ProductSubCategory> getAllSubCategories() {
        return subCategoryRepository.findAll();
    }

    @PostMapping("/subcategories")
    public ResponseEntity<ProductSubCategory> createSubCategory(@RequestBody ProductSubCategory body) {
        if (body.getCategory() == null || body.getCategory().getCategoryName() == null) {
            return ResponseEntity.badRequest().build();
        }
        Optional<ProductCategory> cat = productCategoryRepository.findById(body.getCategory().getCategoryName());
        if (cat.isEmpty()) return ResponseEntity.badRequest().build();
        body.setCategory(cat.get());
        body.setId(null);
        return ResponseEntity.ok(subCategoryRepository.save(body));
    }

    @PutMapping("/subcategories/{id}")
    public ResponseEntity<ProductSubCategory> updateSubCategory(
            @PathVariable Integer id, @RequestBody ProductSubCategory body) {
        return subCategoryRepository.findById(id).map(sc -> {
            sc.setSubCatName(body.getSubCatName());
            sc.setIsOnline(body.getIsOnline());
            if (body.getCategory() != null && body.getCategory().getCategoryName() != null) {
                productCategoryRepository.findById(body.getCategory().getCategoryName())
                    .ifPresent(sc::setCategory);
            }
            return ResponseEntity.ok(subCategoryRepository.save(sc));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/subcategories/{id}")
    public ResponseEntity<Void> deleteSubCategory(@PathVariable Integer id) {
        if (!subCategoryRepository.existsById(id)) return ResponseEntity.notFound().build();
        subCategoryRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // ── Bulk re-embed all products (call once after switching embedding model) ──

    @PostMapping("/reembed-all")
    public ResponseEntity<?> reembedAll() {
        List<Product> all = productService.getAllProducts();
        int triggered = 0;
        for (Product p : all) {
            try {
                String imageUrl = productImageRepository
                    .findByProductProductIdOrderByCreatedAtAsc(p.getProductId()).stream()
                    .findFirst()
                    .map(img -> img.getImageUrl())
                    .orElse(null);
                productVectorService.triggerEmbeddingAsync(p.getProductId(), imageUrl);
                triggered++;
            } catch (Exception e) {
                System.err.println("[REEMBED] Skipped product " + p.getProductId() + ": " + e.getMessage());
            }
        }
        return ResponseEntity.ok(Map.of("triggered", triggered, "total", all.size()));
    }
}

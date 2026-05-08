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
@CrossOrigin(origins = "http://localhost:4200")
public class ProductController {

    @Autowired
    private ProductService productService;

    @Autowired
    private ProductCategoryService productCategoryService;

    @Autowired
    private ProductImageRepository productImageRepository;

    @GetMapping
    public List<Product> getAllProducts() {
        return productService.getAllProducts();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Product> getProductById(@PathVariable Integer id) {
        Optional<Product> product = productService.getProductById(id);
        if (product.isPresent()) {
            return ResponseEntity.ok(product.get());
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping
    public Product createProduct(@RequestBody Product product) {
        return productService.createProduct(product);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Product> updateProduct(@PathVariable Integer id, @RequestBody Product productDetails) {
        Product updatedProduct = productService.updateProduct(id, productDetails);
        if (updatedProduct != null) {
            return ResponseEntity.ok(updatedProduct);
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteProduct(@PathVariable Integer id) {
        productService.deleteProduct(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/categories")
    public List<ProductCategory> getProductCategories() {
        return productCategoryService.getAllOnlineCategories();
    }

    @GetMapping("/{id}/images")
    public ResponseEntity<List<ProductImage>> getProductImages(@PathVariable Integer id) {
        return ResponseEntity.ok(productImageRepository.findByProductProductIdOrderByCreatedAtAsc(id));
    }

    @PostMapping("/{id}/images")
    public ResponseEntity<ProductImage> addProductImage(
            @PathVariable Integer id,
            @RequestBody Map<String, String> body) {
        Optional<Product> product = productService.getProductById(id);
        if (product.isEmpty()) return ResponseEntity.notFound().build();

        ProductImage img = new ProductImage();
        img.setProduct(product.get());
        img.setImageUrl(body.get("imageUrl"));
        img.setPublicId(body.get("publicId"));
        img.setCreatedAt(LocalDateTime.now());
        return ResponseEntity.ok(productImageRepository.save(img));
    }

    @DeleteMapping("/{id}/images/{imageId}")
    public ResponseEntity<Void> deleteProductImage(
            @PathVariable Integer id,
            @PathVariable Integer imageId) {
        productImageRepository.deleteById(imageId);
        return ResponseEntity.noContent().build();
    }
}

package com.example.mybill.wholesale.controller;

import com.example.mybill.wholesale.dto.WholesaleProductImageRequest;
import com.example.mybill.wholesale.dto.WholesaleProductImageResponse;
import com.example.mybill.wholesale.dto.WholesaleProductRequest;
import com.example.mybill.wholesale.dto.WholesaleProductResponse;
import com.example.mybill.wholesale.service.WholesaleProductService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/wholesale/products")
public class WholesaleProductController {

    @Autowired
    private WholesaleProductService productService;

    @GetMapping
    public List<WholesaleProductResponse> list(@RequestParam(defaultValue = "false") boolean activeOnly,
                                               @RequestParam(required = false) String q,
                                               @RequestParam(defaultValue = "false") boolean inStockOnly) {
        return productService.list(activeOnly, q, inStockOnly);
    }

    @GetMapping("/{id}")
    public WholesaleProductResponse get(@PathVariable Integer id) {
        return productService.get(id);
    }

    @PostMapping
    public ResponseEntity<WholesaleProductResponse> create(@Valid @RequestBody WholesaleProductRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(productService.create(request));
    }

    @PutMapping("/{id}")
    public WholesaleProductResponse update(@PathVariable Integer id, @Valid @RequestBody WholesaleProductRequest request) {
        return productService.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Integer id) {
        productService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/images")
    public ResponseEntity<WholesaleProductImageResponse> addImage(@PathVariable Integer id,
                                                                  @Valid @RequestBody WholesaleProductImageRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(productService.addImage(id, request));
    }

    @DeleteMapping("/{id}/images/{imageId}")
    public ResponseEntity<Void> deleteImage(@PathVariable Integer id, @PathVariable Integer imageId) {
        productService.deleteImage(id, imageId);
        return ResponseEntity.noContent().build();
    }
}

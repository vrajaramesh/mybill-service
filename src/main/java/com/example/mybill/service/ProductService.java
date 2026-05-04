package com.example.mybill.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class ProductService {

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductCategoryRepository productCategoryRepository;

    public List<Product> getAllProducts() {
        return productRepository.findAll();
    }

    public Optional<Product> getProductById(Integer id) {
        return productRepository.findById(id);
    }

    public Product createProduct(Product product) {
        // Set timestamps
        LocalDateTime now = LocalDateTime.now();
        product.setCreatedAt(now);
        product.setUpdatedAt(now);

        // Handle category lookup if categoryName is provided
        if (product.getCategory() != null && product.getCategory().getCategoryName() != null) {
            Optional<ProductCategory> category = productCategoryRepository.findById(product.getCategory().getCategoryName());
            if (category.isPresent()) {
                product.setCategory(category.get());
            }
        }

        // Generate custom product ID
        Optional<Integer> maxId = productRepository.findMaxProductId();
        int newId = maxId.isPresent() ? maxId.get() + 1 : 1000;
        if (newId > 9999) {
            throw new RuntimeException("Product ID limit reached. Cannot create more products.");
        }
        product.setProductId(newId);

        return productRepository.save(product);
    }

    public Product updateProduct(Integer id, Product productDetails) {
        Optional<Product> optionalProduct = productRepository.findById(id);
        if (optionalProduct.isPresent()) {
            Product product = optionalProduct.get();
            product.setProductName(productDetails.getProductName());
            product.setDescription(productDetails.getDescription());

            // Handle category lookup if categoryName is provided
            if (productDetails.getCategory() != null && productDetails.getCategory().getCategoryName() != null) {
                Optional<ProductCategory> category = productCategoryRepository.findById(productDetails.getCategory().getCategoryName());
                if (category.isPresent()) {
                    product.setCategory(category.get());
                }
            }

            product.setUnit(productDetails.getUnit());
            product.setCostPrice(productDetails.getCostPrice());
            product.setSellingPrice(productDetails.getSellingPrice());
            product.setStockQuantity(productDetails.getStockQuantity());
            product.setMinStockLevel(productDetails.getMinStockLevel());
            product.setIsActive(productDetails.getIsActive());
            product.setUpdatedAt(LocalDateTime.now());
            return productRepository.save(product);
        }
        return null;
    }

    public void deleteProduct(Integer id) {
        productRepository.deleteById(id);
    }
}

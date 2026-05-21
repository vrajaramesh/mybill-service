package com.example.mybill.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class ProductService {

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductCategoryRepository productCategoryRepository;

    @Autowired
    private ProductSubCategoryRepository productSubCategoryRepository;

    public List<Product> getAllProducts() {
        return productRepository.findAllWithSubCategories();
    }

    public Optional<Product> getProductById(Integer id) {
        return productRepository.findById(id);
    }

    @Transactional
    public Product createProduct(Product product) {
        LocalDateTime now = LocalDateTime.now();
        product.setCreatedAt(now);
        product.setUpdatedAt(now);

        if (product.getCategory() != null && product.getCategory().getCategoryName() != null) {
            productCategoryRepository.findById(product.getCategory().getCategoryName())
                .ifPresent(product::setCategory);
        }

        product.setSubCategory(resolveSubCategory(product.getSubCategory()));

        Optional<Integer> maxId = productRepository.findMaxProductId();
        int newId = maxId.isPresent() ? maxId.get() + 1 : 1000;
        if (newId > 9999) {
            throw new RuntimeException("Product ID limit reached. Cannot create more products.");
        }
        product.setProductId(newId);

        return productRepository.save(product);
    }

    @Transactional
    public Product updateProduct(Integer id, Product productDetails) {
        Optional<Product> optionalProduct = productRepository.findById(id);
        if (optionalProduct.isPresent()) {
            Product product = optionalProduct.get();
            product.setProductName(productDetails.getProductName());
            product.setDescription(productDetails.getDescription());

            if (productDetails.getCategory() != null && productDetails.getCategory().getCategoryName() != null) {
                productCategoryRepository.findById(productDetails.getCategory().getCategoryName())
                    .ifPresent(product::setCategory);
            } else {
                product.setCategory(null);
            }

            product.setSubCategory(resolveSubCategory(productDetails.getSubCategory()));

            product.setUnit(productDetails.getUnit());
            product.setCostPrice(productDetails.getCostPrice());
            product.setSellingPrice(productDetails.getSellingPrice());
            product.setStockQuantity(productDetails.getStockQuantity());
            product.setMinStockLevel(productDetails.getMinStockLevel());
            product.setIsActive(productDetails.getIsActive());
            product.setIsOnline(productDetails.getIsOnline() != null ? productDetails.getIsOnline() : true);
            product.setSuitableFor(productDetails.getSuitableFor());
            product.setTags(productDetails.getTags());
            product.setUpdatedAt(LocalDateTime.now());
            return productRepository.save(product);
        }
        return null;
    }

    public void deleteProduct(Integer id) {
        productRepository.deleteById(id);
    }

    private ProductSubCategory resolveSubCategory(ProductSubCategory incoming) {
        if (incoming == null || incoming.getId() == null) return null;
        return productSubCategoryRepository.findById(incoming.getId()).orElse(null);
    }
}

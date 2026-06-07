package com.example.mybill.service;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProductImageRepository extends JpaRepository<ProductImage, Integer> {
    List<ProductImage> findByProductProductIdOrderByCreatedAtAsc(Integer productId);
    List<ProductImage> findByProductProductIdOrderBySortOrderAscCreatedAtAsc(Integer productId);
}
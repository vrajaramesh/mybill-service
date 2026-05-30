package com.example.mybill.service;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProductSubCategoryRepository extends JpaRepository<ProductSubCategory, Integer> {
    List<ProductSubCategory> findByCategoryCategoryName(String categoryName);
}

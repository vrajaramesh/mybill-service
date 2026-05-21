package com.example.mybill.service;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductRepository extends JpaRepository<Product, Integer> {

    @Query("SELECT MAX(p.productId) FROM Product p")
    Optional<Integer> findMaxProductId();

    @Query("SELECT p FROM Product p LEFT JOIN FETCH p.subCategory LEFT JOIN FETCH p.category")
    List<Product> findAllWithSubCategories();
}

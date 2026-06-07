package com.example.mybill.service;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ProductModelConfigRepository extends JpaRepository<ProductModelConfig, Long> {
    Optional<ProductModelConfig> findByGarmentTypeIgnoreCase(String garmentType);
}

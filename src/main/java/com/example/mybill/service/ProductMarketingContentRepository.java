package com.example.mybill.service;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProductMarketingContentRepository extends JpaRepository<ProductMarketingContent, Integer> {
}

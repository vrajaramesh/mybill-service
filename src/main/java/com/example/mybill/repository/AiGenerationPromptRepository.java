package com.example.mybill.service;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AiGenerationPromptRepository extends JpaRepository<AiGenerationPrompt, Long> {

    // Category-specific match first, then null/blank-category (catch-all)
    @Query("""
        SELECT p FROM AiGenerationPrompt p
        WHERE p.suitableFor = :suitableFor
          AND (p.category = :category OR p.category IS NULL OR p.category = '')
        ORDER BY p.category NULLS LAST
        """)
    List<AiGenerationPrompt> findBySuitableForAndCategory(
            @Param("suitableFor") String suitableFor,
            @Param("category") String category);

    List<AiGenerationPrompt> findBySuitableFor(String suitableFor);
}

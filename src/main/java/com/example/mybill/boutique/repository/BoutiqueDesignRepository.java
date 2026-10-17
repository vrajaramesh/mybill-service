package com.example.mybill.boutique.repository;

import com.example.mybill.boutique.entity.BoutiqueDesign;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface BoutiqueDesignRepository extends JpaRepository<BoutiqueDesign, Integer> {

    Optional<BoutiqueDesign> findByCodeIgnoreCase(String code);

    /** Newest active designs that have a code (the agent can only refer to designs by code). */
    @Query("""
        SELECT d FROM BoutiqueDesign d
         WHERE d.isActive = TRUE AND d.code IS NOT NULL
         ORDER BY d.createdAt DESC NULLS LAST, d.designId DESC""")
    List<BoutiqueDesign> findLatestActive(Pageable page);
}

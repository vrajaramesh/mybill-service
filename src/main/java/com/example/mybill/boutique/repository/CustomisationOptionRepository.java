package com.example.mybill.boutique.repository;

import com.example.mybill.boutique.entity.CustomisationOption;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CustomisationOptionRepository extends JpaRepository<CustomisationOption, Integer> {

    /** Active options offered for a design: its own plus the global ones (design_id NULL). */
    @Query("""
        SELECT o FROM CustomisationOption o
         WHERE o.isActive = TRUE AND (o.designId = :designId OR o.designId IS NULL)
         ORDER BY o.optionGroup, o.sortOrder, o.optionId""")
    List<CustomisationOption> findActiveForDesign(@Param("designId") Integer designId);
}

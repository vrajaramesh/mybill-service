package com.example.mybill.service;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface BoutiqueOrderImageRepository extends JpaRepository<BoutiqueOrderImage, Integer> {
    List<BoutiqueOrderImage> findByItemItemIdOrderByCreatedAtAsc(Integer itemId);
}

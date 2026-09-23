package com.example.mybill.repository;

import com.example.mybill.dto.DebitNote;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface DebitNoteRepository extends JpaRepository<DebitNote, Integer> {
    List<DebitNote> findByPurchase_PurchaseIdOrderByCreatedAtDesc(Integer purchaseId);
    boolean existsByPurchase_PurchaseId(Integer purchaseId);
}
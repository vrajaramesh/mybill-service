package com.example.mybill.service;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PurchaseItemRepository extends JpaRepository<PurchaseItem, Integer> {

    List<PurchaseItem> findByPurchase_PurchaseId(Integer purchaseId);

    List<PurchaseItem> findByProduct_ProductId(Integer productId);

    void deleteByPurchase_PurchaseId(Integer purchaseId);
}

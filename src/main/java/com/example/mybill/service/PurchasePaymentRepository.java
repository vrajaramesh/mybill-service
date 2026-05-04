package com.example.mybill.service;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PurchasePaymentRepository extends JpaRepository<PurchasePayment, Integer> {

    List<PurchasePayment> findByPurchase_PurchaseId(Integer purchaseId);

    List<PurchasePayment> findByPurchase_PurchaseIdOrderByPaymentDateDesc(Integer purchaseId);
}

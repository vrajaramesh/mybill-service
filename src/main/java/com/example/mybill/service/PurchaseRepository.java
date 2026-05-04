package com.example.mybill.service;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PurchaseRepository extends JpaRepository<Purchase, Integer> {

    @Query("SELECT p FROM Purchase p ORDER BY p.createdAt DESC")
    List<Purchase> findAllOrderByCreatedAtDesc();

    List<Purchase> findBySupplier_SupplierId(Integer supplierId);

    List<Purchase> findByPaymentStatus(String paymentStatus);
}

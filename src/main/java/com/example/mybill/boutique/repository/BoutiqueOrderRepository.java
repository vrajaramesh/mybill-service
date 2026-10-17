package com.example.mybill.boutique.repository;

import com.example.mybill.boutique.entity.BoutiqueOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface BoutiqueOrderRepository extends JpaRepository<BoutiqueOrder, Integer> {

    Optional<BoutiqueOrder> findByOrderNo(String orderNo);

    Optional<BoutiqueOrder> findByIdempotencyKey(String idempotencyKey);

    /** Next number from the per-firm sequence (resolved through the tenant's search_path). */
    @Query(value = "SELECT nextval('boutique_order_no_seq')", nativeQuery = true)
    long nextOrderNumber();
}

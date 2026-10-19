package com.example.mybill.boutique.repository;

import com.example.mybill.boutique.entity.BoutiqueOrder;
import com.example.mybill.boutique.entity.BoutiqueOrderStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface BoutiqueOrderRepository extends JpaRepository<BoutiqueOrder, Integer> {

    Optional<BoutiqueOrder> findByOrderNo(String orderNo);

    Optional<BoutiqueOrder> findByIdempotencyKey(String idempotencyKey);

    List<BoutiqueOrder> findAllByOrderByCreatedAtDescOrderIdDesc(Pageable page);

    List<BoutiqueOrder> findByStatusOrderByCreatedAtDescOrderIdDesc(BoutiqueOrderStatus status, Pageable page);

    List<BoutiqueOrder> findByCustomerIdOrderByCreatedAtDescOrderIdDesc(Integer customerId, Pageable page);

    List<BoutiqueOrder> findByMeasurementStatusOrderByPaidAtAscOrderIdAsc(String measurementStatus, Pageable page);

    long countByMeasurementStatus(String measurementStatus);

    /** Next number from the per-firm sequence (resolved through the tenant's search_path). */
    @Query(value = "SELECT nextval('boutique_order_no_seq')", nativeQuery = true)
    long nextOrderNumber();
}

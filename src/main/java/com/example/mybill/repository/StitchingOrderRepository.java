package com.example.mybill.service;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.List;

@Repository
public interface StitchingOrderRepository extends JpaRepository<StitchingOrder, Integer> {

    List<StitchingOrder> findAllByOrderByCreatedAtDesc();

    List<StitchingOrder> findByStatusOrderByDeliveryDateAsc(String status);

    @Query("SELECT o FROM StitchingOrder o WHERE " +
           "(:status IS NULL OR o.status = :status) AND " +
           "(:from IS NULL OR o.orderDate >= :from) AND " +
           "(:to IS NULL OR o.orderDate <= :to) " +
           "ORDER BY o.createdAt DESC")
    List<StitchingOrder> findFiltered(String status, LocalDate from, LocalDate to);

    @Query("SELECT o FROM StitchingOrder o WHERE " +
           "o.deliveryDate < :today AND " +
           "o.status NOT IN ('DELIVERED','CANCELLED') " +
           "ORDER BY o.deliveryDate ASC")
    List<StitchingOrder> findOverdueOrders(LocalDate today);

    long countByStatus(String status);
}

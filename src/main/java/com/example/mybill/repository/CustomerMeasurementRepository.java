package com.example.mybill.service;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface CustomerMeasurementRepository extends JpaRepository<CustomerMeasurement, Integer> {
    List<CustomerMeasurement> findByCustomer_CustomerIdOrderByCreatedAtDesc(Integer customerId);
}

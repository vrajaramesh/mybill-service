package com.example.mybill.boutique.repository;

import com.example.mybill.boutique.entity.BoutiqueMeasurement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BoutiqueMeasurementRepository extends JpaRepository<BoutiqueMeasurement, Integer> {

    /** The customer's current profile = newest row. */
    Optional<BoutiqueMeasurement> findFirstByCustomerIdOrderByCreatedAtDescMeasurementIdDesc(Integer customerId);
}

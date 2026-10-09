package com.example.mybill.wholesale.repository;

import com.example.mybill.wholesale.entity.WholesaleStockAdjustment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WholesaleStockAdjustmentRepository extends JpaRepository<WholesaleStockAdjustment, Integer> {

    List<WholesaleStockAdjustment> findTop200ByOrderByAdjustmentDateDescAdjustmentIdDesc();

    boolean existsByAdjustmentNumber(String adjustmentNumber);
}

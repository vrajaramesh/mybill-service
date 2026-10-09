package com.example.mybill.wholesale.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record WholesaleStockAdjustmentResponse(
    Integer adjustmentId,
    String adjustmentNumber,
    LocalDate adjustmentDate,
    String reason,
    Boolean isOpening,
    LocalDateTime createdAt,
    String createdBy,
    List<Line> items
) {
    public record Line(Integer wholesaleProductId, String productName, String unit, BigDecimal quantityChange, String notes) {}
}

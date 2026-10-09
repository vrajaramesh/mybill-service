package com.example.mybill.wholesale.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record WholesalePriceRuleResponse(
    Integer priceRuleId,
    Integer wholesaleProductId,
    BigDecimal minQuantity,
    BigDecimal maxQuantity,
    BigDecimal price,
    BigDecimal gstPct,
    Boolean isActive,
    LocalDate effectiveFrom,
    LocalDate effectiveTo,
    String notes,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {}

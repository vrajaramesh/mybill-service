package com.example.mybill.wholesale.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** inSync = the ledger (Σ in − Σ out) agrees with the product's available quantity. */
public record WholesaleCurrentStockRow(
    Integer wholesaleProductId,
    String productCode,
    String productName,
    String productType,
    String unit,
    Boolean isActive,
    BigDecimal availableQuantity,
    BigDecimal ledgerQuantity,
    Boolean inSync,
    BigDecimal purchaseRate,
    BigDecimal stockValue,
    LocalDate lastMovementDate
) {}

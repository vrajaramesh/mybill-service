package com.example.mybill.wholesale.dto;

import java.math.BigDecimal;

/** Per-product movement for a date range: opening + in − out = closing (by transaction date). */
public record WholesaleStockMovementRow(
    Integer wholesaleProductId,
    String productCode,
    String productName,
    String unit,
    BigDecimal openingQuantity,
    BigDecimal quantityIn,
    BigDecimal quantityOut,
    BigDecimal closingQuantity
) {}

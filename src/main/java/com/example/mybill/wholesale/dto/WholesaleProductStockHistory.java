package com.example.mybill.wholesale.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** One product's stock history between two dates (entries oldest first). */
public record WholesaleProductStockHistory(
    Integer wholesaleProductId,
    String productCode,
    String productName,
    String unit,
    BigDecimal availableQuantity,
    LocalDate fromDate,
    LocalDate toDate,
    BigDecimal openingQuantity,
    BigDecimal totalIn,
    BigDecimal totalOut,
    BigDecimal closingQuantity,
    List<WholesaleLedgerEntryResponse> entries
) {}

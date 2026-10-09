package com.example.mybill.wholesale.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** One stock movement with its source document (referenceType + referenceId + referenceNumber). */
public record WholesaleLedgerEntryResponse(
    Long ledgerId,
    Integer wholesaleProductId,
    String productCode,
    String productName,
    String unit,
    String transactionType,
    String referenceType,
    Integer referenceId,
    String referenceNumber,
    Long reversesLedgerId,
    BigDecimal quantityIn,
    BigDecimal quantityOut,
    /** Stock right after this movement was posted. */
    BigDecimal balanceQuantity,
    BigDecimal rate,
    LocalDate transactionDate,
    String notes,
    LocalDateTime createdAt,
    String createdBy
) {}

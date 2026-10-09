package com.example.mybill.wholesale.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record WholesalePurchaseResponse(
    Integer wholesalePurchaseId,
    Integer supplierId,
    String supplierName,
    String supplierGstNumber,
    String invoiceNumber,
    LocalDate invoiceDate,
    Boolean interstate,
    BigDecimal taxableAmount,
    BigDecimal cgstAmount,
    BigDecimal sgstAmount,
    BigDecimal igstAmount,
    BigDecimal gstAmount,
    BigDecimal totalAmount,
    BigDecimal paidAmount,
    String paymentStatus,
    LocalDate paymentDueDate,
    String notes,
    LocalDateTime createdAt,
    List<WholesalePurchaseItemResponse> items,
    /** ACTIVE or CANCELLED. */
    String status,
    String createdBy,
    LocalDateTime cancelledAt,
    String cancelledBy,
    String cancelReason
) {}

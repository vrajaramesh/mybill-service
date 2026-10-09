package com.example.mybill.wholesale.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record WholesaleSalesDocumentSummary(
    Integer salesDocumentId,
    String docType,
    String docTypeLabel,
    String documentNumber,
    LocalDate documentDate,
    String status,
    Integer wholesaleCustomerId,
    String customerName,
    String customerBusinessName,
    BigDecimal grandTotal,
    BigDecimal amountPaid,
    BigDecimal balanceDue,
    String paymentStatus,
    String paymentMode,
    LocalDate dueDate,
    Integer sourceQuotationId,
    String sourceQuotationNumber,
    String referenceNumber,
    String valueEffect
) {}

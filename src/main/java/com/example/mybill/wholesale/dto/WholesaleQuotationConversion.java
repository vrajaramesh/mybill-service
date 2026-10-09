package com.example.mybill.wholesale.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** A Sales Receipt / Credit Note created from a quotation (shown on the quotation). */
public record WholesaleQuotationConversion(
    Integer salesDocumentId,
    String docType,
    String docTypeLabel,
    String documentNumber,
    LocalDate documentDate,
    String status,
    BigDecimal grandTotal,
    LocalDateTime convertedAt,
    String convertedBy
) {}

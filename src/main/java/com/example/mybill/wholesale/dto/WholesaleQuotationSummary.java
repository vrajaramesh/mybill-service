package com.example.mybill.wholesale.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Row in the quotation list. */
public record WholesaleQuotationSummary(
    Integer quotationId,
    String quotationNumber,
    String displayNumber,
    String status,
    LocalDate quotationDate,
    LocalDate validUntil,
    Integer wholesaleCustomerId,
    String customerName,
    String customerBusinessName,
    BigDecimal grandTotal
) {}

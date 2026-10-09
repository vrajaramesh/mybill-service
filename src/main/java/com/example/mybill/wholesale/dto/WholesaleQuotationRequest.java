package com.example.mybill.wholesale.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Create / update (DRAFT only) / preview payload. validUntil defaults to quotationDate + configured validity days. */
public record WholesaleQuotationRequest(
    @NotNull(message = "Select a customer")
    Integer customerId,

    @NotNull(message = "Quotation date is required")
    LocalDate quotationDate,

    LocalDate validUntil,

    @Size(max = 60, message = "Other charges label must be at most 60 characters")
    String otherChargesLabel,

    @DecimalMin(value = "0", message = "Other charges cannot be negative")
    @Digits(integer = 12, fraction = 2, message = "Other charges allow at most 2 decimals")
    BigDecimal otherChargesAmount,

    @DecimalMin(value = "0", message = "Other charges GST % cannot be negative")
    @DecimalMax(value = "100", message = "Other charges GST % cannot exceed 100")
    BigDecimal otherChargesGstPct,

    String notes,

    String terms,

    @NotEmpty(message = "Add at least one item")
    @Size(max = 200, message = "A quotation can have at most 200 items")
    List<@Valid @NotNull WholesaleQuotationItemRequest> items
) {}

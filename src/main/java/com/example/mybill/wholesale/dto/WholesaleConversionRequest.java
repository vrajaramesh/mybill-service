package com.example.mybill.wholesale.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Convert a quotation. SALES_RECEIPT needs paymentMode when something is received (amountReceived, default = full); CREDIT_NOTE (credit sale) uses dueDate
 * (default: document date + configured due days). allowDuplicate must be true to convert an already-converted quotation again.
 */
public record WholesaleConversionRequest(
    @NotBlank(message = "Choose what to convert to")
    @Pattern(regexp = "SALES_RECEIPT|CREDIT_NOTE", message = "Convert to must be SALES_RECEIPT or CREDIT_NOTE")
    String convertTo,

    LocalDate documentDate,

    @Pattern(regexp = "^$|CASH|UPI|BANK_TRANSFER|CARD|CHEQUE", message = "Payment mode must be CASH, UPI, BANK_TRANSFER, CARD or CHEQUE")
    String paymentMode,

    @Size(max = 100, message = "Payment reference must be at most 100 characters")
    String paymentReference,

    /** Sales Receipt: amount received now (default: the full grand total; 0 = nothing received yet). */
    @DecimalMin(value = "0", message = "Amount received cannot be negative")
    @Digits(integer = 12, fraction = 2, message = "Amount received allows at most 2 decimals")
    BigDecimal amountReceived,

    LocalDate dueDate,

    String notes,

    Boolean allowDuplicate
) {}

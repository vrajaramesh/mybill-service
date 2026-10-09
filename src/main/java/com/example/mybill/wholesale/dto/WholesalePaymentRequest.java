package com.example.mybill.wholesale.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Record a payment. paymentDate defaults to today; amount cannot exceed the balance due. */
public record WholesalePaymentRequest(
    LocalDate paymentDate,

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "0", inclusive = false, message = "Amount must be greater than 0")
    @Digits(integer = 12, fraction = 2, message = "Amount allows at most 2 decimals")
    BigDecimal amount,

    @NotBlank(message = "Payment method is required")
    @Pattern(regexp = "CASH|UPI|BANK_TRANSFER|CARD|CHEQUE", message = "Payment method must be CASH, UPI, BANK_TRANSFER, CARD or CHEQUE")
    String paymentMode,

    @Size(max = 100, message = "Reference must be at most 100 characters")
    String reference,

    @Size(max = 255, message = "Notes must be at most 255 characters")
    String notes
) {}

package com.example.mybill.wholesale.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record WholesalePurchaseRequest(
    @NotNull(message = "Supplier is required")
    Integer supplierId,

    @NotBlank(message = "Invoice number is required")
    @Size(max = 100, message = "Invoice number must be at most 100 characters")
    String invoiceNumber,

    @NotNull(message = "Invoice date is required")
    LocalDate invoiceDate,

    /** true = IGST, false/null = CGST + SGST. */
    Boolean interstate,

    @DecimalMin(value = "0", message = "Paid amount cannot be negative")
    BigDecimal paidAmount,

    LocalDate paymentDueDate,

    String notes,

    @NotEmpty(message = "Add at least one item")
    List<@Valid @NotNull WholesalePurchaseItemRequest> items
) {}

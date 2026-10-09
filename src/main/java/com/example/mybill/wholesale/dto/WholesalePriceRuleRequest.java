package com.example.mybill.wholesale.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One quantity slab: [minQuantity, maxQuantity] (inclusive, max null = "and above") -> price per unit excl. GST. */
public record WholesalePriceRuleRequest(
    @NotNull(message = "Minimum quantity is required")
    @DecimalMin(value = "0", inclusive = false, message = "Minimum quantity must be greater than 0")
    @Digits(integer = 9, fraction = 3, message = "Quantity allows at most 3 decimals")
    BigDecimal minQuantity,

    @DecimalMin(value = "0", inclusive = false, message = "Maximum quantity must be greater than 0")
    @Digits(integer = 9, fraction = 3, message = "Quantity allows at most 3 decimals")
    BigDecimal maxQuantity,

    @NotNull(message = "Price is required")
    @DecimalMin(value = "0", message = "Price cannot be negative")
    @Digits(integer = 10, fraction = 2, message = "Price allows at most 2 decimals")
    BigDecimal price,

    /** Optional; when empty the product's GST % applies. */
    @DecimalMin(value = "0", message = "GST % cannot be negative")
    @DecimalMax(value = "100", message = "GST % cannot exceed 100")
    BigDecimal gstPct,

    Boolean isActive,

    LocalDate effectiveFrom,

    LocalDate effectiveTo,

    @Size(max = 255, message = "Notes must be at most 255 characters")
    String notes
) {}

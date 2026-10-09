package com.example.mybill.wholesale.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;

/**
 * One quotation line. rate empty -> the applicable quantity price rule on the quotation date is used.
 * gstPct empty -> the rule's GST %, else the product's GST %.
 */
public record WholesaleQuotationItemRequest(
    @NotNull(message = "Select a product")
    Integer wholesaleProductId,

    @NotNull(message = "Quantity is required")
    @DecimalMin(value = "0", inclusive = false, message = "Quantity must be greater than 0")
    @Digits(integer = 9, fraction = 3, message = "Quantity allows at most 3 decimals")
    BigDecimal quantity,

    @DecimalMin(value = "0", message = "Rate cannot be negative")
    @Digits(integer = 10, fraction = 2, message = "Rate allows at most 2 decimals")
    BigDecimal rate,

    @DecimalMin(value = "0", message = "Discount % cannot be negative")
    @DecimalMax(value = "100", message = "Discount % cannot exceed 100")
    BigDecimal discountPct,

    @DecimalMin(value = "0", message = "GST % cannot be negative")
    @DecimalMax(value = "100", message = "GST % cannot exceed 100")
    BigDecimal gstPct,

    @Size(max = 500, message = "Item description must be at most 500 characters")
    String description
) {}

package com.example.mybill.wholesale.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;

/**
 * One purchase line. Exactly one of wholesaleProductId (existing wholesale product)
 * or newProduct (create a wholesale product as part of this purchase) must be given.
 */
public record WholesalePurchaseItemRequest(
    Integer wholesaleProductId,

    @Valid
    WholesaleProductRequest newProduct,

    @NotNull(message = "Quantity is required")
    @DecimalMin(value = "0", inclusive = false, message = "Quantity must be greater than 0")
    @Digits(integer = 9, fraction = 3, message = "Quantity allows at most 3 decimals")
    BigDecimal quantity,

    @NotNull(message = "Purchase rate is required")
    @DecimalMin(value = "0", message = "Purchase rate cannot be negative")
    @Digits(integer = 10, fraction = 2, message = "Purchase rate allows at most 2 decimals")
    BigDecimal purchaseRate,

    @NotNull(message = "GST % is required")
    @DecimalMin(value = "0", message = "GST % cannot be negative")
    @DecimalMax(value = "100", message = "GST % cannot exceed 100")
    BigDecimal gstPct
) {}

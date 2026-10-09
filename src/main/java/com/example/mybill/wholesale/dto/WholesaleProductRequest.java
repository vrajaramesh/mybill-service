package com.example.mybill.wholesale.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;

/** Create/update payload for a wholesale product. Quantity is not accepted: stock changes only via purchases. */
public record WholesaleProductRequest(
    @Size(max = 50, message = "Product code must be at most 50 characters")
    String productCode,

    @NotBlank(message = "Product name is required")
    @Size(max = 200, message = "Product name must be at most 200 characters")
    String productName,

    @Size(max = 100, message = "Product type must be at most 100 characters")
    String productType,

    @Pattern(regexp = "^$|^\\d{4}(\\d{2})?(\\d{2})?$", message = "HSN code must be 4, 6 or 8 digits")
    String hsnCode,

    String description,

    @Size(max = 200, message = "Material must be at most 200 characters")
    String material,

    @NotBlank(message = "Unit is required")
    @Size(max = 20, message = "Unit must be at most 20 characters")
    String unit,

    @DecimalMin(value = "0", message = "Purchase rate cannot be negative")
    @Digits(integer = 10, fraction = 2, message = "Purchase rate allows at most 2 decimals")
    BigDecimal purchaseRate,

    @DecimalMin(value = "0", message = "GST % cannot be negative")
    @DecimalMax(value = "100", message = "GST % cannot exceed 100")
    BigDecimal gstPct,

    Integer supplierId,

    Boolean isActive,

    @Size(max = 100, message = "Fabric type must be at most 100 characters")
    String fabricType,

    @Size(max = 1000, message = "Colours must be at most 1000 characters")
    String availableColors,

    @Size(max = 1000, message = "Designs must be at most 1000 characters")
    String availableDesigns,

    @Size(max = 4000, message = "Specifications must be at most 4000 characters")
    String specifications
) {}

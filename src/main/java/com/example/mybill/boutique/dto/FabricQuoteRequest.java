package com.example.mybill.boutique.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

/** Price for metres of one fabric (products row), optionally with delivery to a pincode. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record FabricQuoteRequest(
    @NotNull(message = "product_id is required")
    Integer productId,
    @NotNull(message = "metres is required")
    @DecimalMin(value = "0.5", message = "metres must be at least 0.5")
    @DecimalMax(value = "100", message = "metres must be at most 100")
    BigDecimal metres,
    @Pattern(regexp = "^$|^[1-9][0-9]{5}$", message = "pincode must be 6 digits and cannot start with 0")
    String pincode
) {}

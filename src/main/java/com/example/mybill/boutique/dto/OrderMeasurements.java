package com.example.mybill.boutique.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;

/** Measurements given with an order; stored as the order item's snapshot. Same ranges as MeasurementRequest. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OrderMeasurements(
    @Size(max = 10, message = "measurements.size_code is too long")
    String sizeCode,

    @DecimalMin(value = "120.0", message = "measurements.height_cm must be between 120 and 200")
    @DecimalMax(value = "200.0", message = "measurements.height_cm must be between 120 and 200")
    BigDecimal heightCm,

    @Pattern(regexp = "^(?i)(SNUG|REGULAR|RELAXED)$", message = "measurements.fit_preference must be SNUG, REGULAR or RELAXED")
    String fitPreference,

    @DecimalMin(value = "24.0", message = "measurements.bust_in must be between 24 and 60 inches")
    @DecimalMax(value = "60.0", message = "measurements.bust_in must be between 24 and 60 inches")
    BigDecimal bustIn,

    @DecimalMin(value = "20.0", message = "measurements.waist_in must be between 20 and 56 inches")
    @DecimalMax(value = "56.0", message = "measurements.waist_in must be between 20 and 56 inches")
    BigDecimal waistIn,

    @DecimalMin(value = "26.0", message = "measurements.hip_in must be between 26 and 64 inches")
    @DecimalMax(value = "64.0", message = "measurements.hip_in must be between 26 and 64 inches")
    BigDecimal hipIn,

    @Size(max = 500, message = "measurements.notes must be at most 500 characters")
    String notes
) {}

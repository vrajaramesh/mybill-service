package com.example.mybill.boutique.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;

/** A customer's size profile. Ranges reject obvious typos (e.g. cm entered as inches). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record MeasurementRequest(
    @NotBlank(message = "size_code is required")
    String sizeCode,

    @DecimalMin(value = "120.0", message = "height_cm must be between 120 and 200")
    @DecimalMax(value = "200.0", message = "height_cm must be between 120 and 200")
    BigDecimal heightCm,

    @DecimalMin(value = "24.0", message = "bust_in must be between 24 and 60 inches")
    @DecimalMax(value = "60.0", message = "bust_in must be between 24 and 60 inches")
    BigDecimal bustIn,

    @DecimalMin(value = "20.0", message = "waist_in must be between 20 and 56 inches")
    @DecimalMax(value = "56.0", message = "waist_in must be between 20 and 56 inches")
    BigDecimal waistIn,

    @DecimalMin(value = "26.0", message = "hip_in must be between 26 and 64 inches")
    @DecimalMax(value = "64.0", message = "hip_in must be between 26 and 64 inches")
    BigDecimal hipIn,

    @Pattern(regexp = "^(?i)(SNUG|REGULAR|RELAXED)$", message = "fit_preference must be SNUG, REGULAR or RELAXED")
    String fitPreference,

    @Size(max = 500, message = "notes must be at most 500 characters")
    String notes
) {}

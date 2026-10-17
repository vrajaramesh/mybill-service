package com.example.mybill.boutique.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record MeasurementResponse(Integer measurementId, Integer customerId, String sizeCode, BigDecimal heightCm,
                                  BigDecimal bustIn, BigDecimal waistIn, BigDecimal hipIn, String fitPreference,
                                  String notes, String source, LocalDateTime createdAt) {}

package com.example.mybill.boutique.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.math.BigDecimal;

/** Body measurements in inches for one standard size. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SizeChartRow(String sizeCode, BigDecimal bustIn, BigDecimal waistIn, BigDecimal hipIn,
                           BigDecimal shoulderIn, BigDecimal lengthIn) {}

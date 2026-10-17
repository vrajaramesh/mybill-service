package com.example.mybill.boutique.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.math.BigDecimal;

/** An option as priced on a quote / order line (also the shape stored in options_snapshot). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SelectedOptionDto(Integer optionId, String optionGroup, String name, BigDecimal priceDelta) {}

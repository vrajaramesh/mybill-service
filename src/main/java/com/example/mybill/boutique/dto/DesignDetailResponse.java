package com.example.mybill.boutique.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.math.BigDecimal;
import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DesignDetailResponse(
    String code,
    String name,
    String garmentType,
    String description,
    String fabricDescription,
    BigDecimal basePrice,
    Integer leadTimeDays,
    String currency,
    List<ImageDto> images,
    List<OptionGroupDto> optionGroups
) {}

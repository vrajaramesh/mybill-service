package com.example.mybill.boutique.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OrderItemResponse(String designCode, String designName, String sizeCode, List<SelectedOptionDto> options,
                                Map<String, Object> measurements, BigDecimal unitPrice, int qty, BigDecimal lineTotal) {}

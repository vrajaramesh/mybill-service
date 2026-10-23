package com.example.mybill.boutique.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.math.BigDecimal;

/** A priced goods line: kind FABRIC (quantity in metres) or READYMADE (pieces of one size). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record GoodsLineResponse(String itemCode, Integer productId, String name, String colour, String kind,
                                String unit, String size, BigDecimal quantity, BigDecimal unitPrice, BigDecimal lineTotal) {}

package com.example.mybill.boutique.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.math.BigDecimal;
import java.util.List;

/**
 * A fabric the agent can sell by the metre: an active, online products row with a metre unit.
 * code is FAB-&lt;product_id&gt;; price_per_metre is the product's selling price; available_metres its stock.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record FabricResponse(Integer productId, String code, String name, String category, String description,
                             BigDecimal pricePerMetre, BigDecimal availableMetres, String currency,
                             List<ImageDto> images) {}

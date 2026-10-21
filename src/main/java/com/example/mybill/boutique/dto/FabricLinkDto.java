package com.example.mybill.boutique.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.math.BigDecimal;

/**
 * A fabric sold separately for a design: a product page on the shop website. Product fields come from the
 * products row named by the link's ?product= id (null when the link has none or the product is gone).
 * in_stock: stock_quantity > 0 (never the exact count).
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record FabricLinkDto(Integer id, String url, Integer productId, String label, String name, BigDecimal price,
                            String unit, Boolean inStock, Boolean online, String imageUrl) {}

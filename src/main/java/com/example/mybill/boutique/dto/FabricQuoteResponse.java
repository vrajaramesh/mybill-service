package com.example.mybill.boutique.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * breakdown: unit_price = price_per_metre, qty = 1, subtotal = price_per_metre x metres, delivery as for garments.
 * estimated_delivery_date = today + dispatch days + transit days (null when not serviceable).
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record FabricQuoteResponse(Integer productId, String code, String name, BigDecimal metres,
                                  BigDecimal pricePerMetre, PriceBreakdown breakdown, boolean serviceable,
                                  Integer transitDays, LocalDate estimatedDeliveryDate) {}

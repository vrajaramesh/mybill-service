package com.example.mybill.boutique.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.math.BigDecimal;
import java.util.List;

/**
 * Server-side price: unit_price = base_price + sum(option price_delta); subtotal = unit_price x qty;
 * delivery_charge is waived when subtotal >= free_delivery_above; total = subtotal + delivery_charge. INR.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record PriceBreakdown(
    BigDecimal basePrice,
    List<SelectedOptionDto> options,
    BigDecimal optionsTotal,
    BigDecimal unitPrice,
    int qty,
    BigDecimal subtotal,
    BigDecimal deliveryCharge,
    BigDecimal freeDeliveryAbove,
    BigDecimal total,
    String currency
) {}

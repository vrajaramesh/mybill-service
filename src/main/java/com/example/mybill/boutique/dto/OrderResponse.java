package com.example.mybill.boutique.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OrderResponse(
    String orderNo,
    String status,
    Integer customerId,
    String channel,
    DeliveryDetails delivery,
    List<OrderItemResponse> items,
    BigDecimal subtotal,
    BigDecimal deliveryCharge,
    BigDecimal total,
    String currency,
    LocalDate estimatedDeliveryDate,
    LocalDateTime createdAt
) {}

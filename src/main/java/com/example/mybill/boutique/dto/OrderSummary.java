package com.example.mybill.boutique.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** One row of the boutique order list (GET /api/agent/orders, GET /api/boutique-orders). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OrderSummary(String orderNo, String status, String channel, Integer customerId, String customerName,
                           String customerPhone, String designCode, String designName, String sizeCode, int qty,
                           BigDecimal total, String currency, LocalDate estimatedDeliveryDate, LocalDateTime createdAt) {}

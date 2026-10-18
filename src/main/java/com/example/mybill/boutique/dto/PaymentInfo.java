package com.example.mybill.boutique.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Latest payment link of an order (GET .../payment, POST .../payment-link, and inside order responses). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record PaymentInfo(String orderNo, String orderStatus, String status, String shortUrl, BigDecimal amount,
                          String currency, LocalDateTime expiresAt, LocalDateTime paidAt, String razorpayPaymentId,
                          boolean needsReview) {}

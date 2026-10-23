package com.example.mybill.boutique.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.time.LocalDate;
import java.util.List;

/** breakdown.subtotal = sum of line totals; delivery as for all orders. order_type FABRIC / READYMADE / MIXED. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record GoodsQuoteResponse(List<GoodsLineResponse> lines, PriceBreakdown breakdown, String orderType,
                                 boolean serviceable, Integer transitDays, LocalDate estimatedDeliveryDate) {}

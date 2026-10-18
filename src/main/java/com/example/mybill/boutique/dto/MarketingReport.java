package com.example.mybill.boutique.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * "Ad performance" for [from, to] (IST dates, inclusive). Conversations are counted by occurred_at, orders by
 * created_at. conversion_pct = paid orders / conversations (null without conversations).
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record MarketingReport(LocalDate from, LocalDate to, List<Row> rows, Row total) {

    /** One marketing link, or the "Unmapped/other" row (link_id null). */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Row(
        Integer linkId,
        String sourceType,
        String sourceId,
        String designCode,
        String label,
        Boolean active,
        long conversations,
        long orders,
        long paidOrders,
        BigDecimal paidRevenue,
        BigDecimal conversionPct
    ) {}
}

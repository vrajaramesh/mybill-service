package com.example.mybill.wholesale.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Result of GET /api/wholesale/products/{id}/price?quantity=... — the applicable slab and the amounts for that quantity. */
public record WholesalePriceQuoteResponse(
    Integer wholesaleProductId,
    String productName,
    String unit,
    BigDecimal quantity,
    LocalDate priceDate,
    Integer priceRuleId,
    BigDecimal minQuantity,
    BigDecimal maxQuantity,
    BigDecimal unitPrice,
    BigDecimal gstPct,
    /** "RULE" when the slab defines GST, "PRODUCT" when the product's GST % was used. */
    String gstSource,
    Boolean interstate,
    BigDecimal taxableAmount,
    BigDecimal cgstAmount,
    BigDecimal sgstAmount,
    BigDecimal igstAmount,
    BigDecimal gstAmount,
    BigDecimal totalAmount,
    BigDecimal availableQuantity,
    Boolean sufficientStock
) {}

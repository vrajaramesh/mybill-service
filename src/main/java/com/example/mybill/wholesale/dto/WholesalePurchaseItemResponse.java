package com.example.mybill.wholesale.dto;

import java.math.BigDecimal;

public record WholesalePurchaseItemResponse(
    Integer wholesalePurchaseItemId,
    Integer wholesaleProductId,
    String productCode,
    String productName,
    String unit,
    String hsnCode,
    BigDecimal quantity,
    BigDecimal purchaseRate,
    BigDecimal gstPct,
    BigDecimal taxableAmount,
    BigDecimal cgstAmount,
    BigDecimal sgstAmount,
    BigDecimal igstAmount,
    BigDecimal gstAmount,
    BigDecimal totalAmount
) {}

package com.example.mybill.wholesale.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record WholesaleProductResponse(
    Integer wholesaleProductId,
    String productCode,
    String productName,
    String productType,
    String hsnCode,
    String description,
    String material,
    String unit,
    BigDecimal availableQuantity,
    BigDecimal purchaseRate,
    BigDecimal gstPct,
    Integer supplierId,
    String supplierName,
    Boolean isActive,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    List<WholesaleProductImageResponse> images,
    /** Number of active price rules; 0 = no wholesale selling price configured yet. */
    Integer activePriceRuleCount,
    String fabricType,
    String availableColors,
    String availableDesigns,
    String specifications
) {}

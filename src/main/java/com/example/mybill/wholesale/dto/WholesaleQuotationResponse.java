package com.example.mybill.wholesale.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Full quotation. firm = business profile snapshot; customer = customer snapshot;
 * allowedActions tells the UI which buttons apply in the current status.
 */
public record WholesaleQuotationResponse(
    Integer quotationId,
    String quotationNumber,
    String displayNumber,
    String status,
    LocalDate quotationDate,
    LocalDate validUntil,
    WholesaleDocumentProfile firm,
    Customer customer,
    String placeOfSupplyStateCode,
    Boolean interstate,
    List<Item> items,
    Totals totals,
    String notes,
    String terms,
    Integer duplicatedFromId,
    String cancelReason,
    LocalDateTime createdAt,
    String createdBy,
    LocalDateTime updatedAt,
    LocalDateTime issuedAt,
    String issuedBy,
    LocalDateTime statusChangedAt,
    String statusChangedBy,
    List<String> allowedActions,
    List<String> warnings,
    /** NOT_CONVERTED, CONVERTED (has an active Sales Receipt / Credit Note) or CONVERSION_CANCELLED. */
    String conversionStatus,
    List<WholesaleQuotationConversion> conversions
) {
    public record Customer(Integer wholesaleCustomerId, String customerName, String businessName, String gstNumber,
                           String phone, String email, String billingAddress, String billingCity,
                           String billingStateCode, String billingStateName, String billingPinCode,
                           String shippingAddress, String shippingCity, String shippingStateCode,
                           String shippingStateName, String shippingPinCode) {}

    public record Item(Integer quotationItemId, Integer lineNo, Integer wholesaleProductId, String itemName,
                       String productCode, String hsnCode, String unit, String description, BigDecimal quantity,
                       BigDecimal rate, String rateSource, BigDecimal ruleRate, Integer priceRuleId,
                       BigDecimal discountPct, BigDecimal grossAmount, BigDecimal discountAmount,
                       BigDecimal taxableAmount, BigDecimal gstPct, BigDecimal cgstAmount, BigDecimal sgstAmount,
                       BigDecimal igstAmount, BigDecimal gstAmount, BigDecimal totalAmount,
                       BigDecimal availableQuantity) {}

    public record Totals(BigDecimal grossAmount, BigDecimal discountAmount, BigDecimal subtotal,
                         String otherChargesLabel, BigDecimal otherChargesAmount, BigDecimal otherChargesGstPct,
                         BigDecimal otherChargesGstAmount, BigDecimal cgstAmount, BigDecimal sgstAmount,
                         BigDecimal igstAmount, BigDecimal gstAmount, BigDecimal roundOff, BigDecimal grandTotal) {}
}

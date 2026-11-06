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
    LocalDateTime paidAt,
    PaymentInfo payment,
    String sourceType,
    String sourceId,
    String ctwaClid,
    String measurementStatus,
    LocalDateTime measurementTakenAt,
    String measurementTakenBy,
    LocalDateTime createdAt,
    /** GARMENT or FABRIC. */
    String orderType,
    /** Courier shipment (null until booked): DELHIVERY, the AWB / tracking number, a public tracking link. */
    String courier,
    String awb,
    String trackingUrl,
    String courierStatus,
    String courierLocation,
    LocalDateTime courierStatusAt,
    LocalDateTime shippedAt,
    LocalDateTime deliveredAt,
    /** Parcel weight (contents + cover) the delivery charge was quoted on. */
    Integer shipWeightGrams,
    /** WHOLESALE orders: GST estimate (total includes it) and the quotation draft in the Wholesale module. */
    BigDecimal gstAmount,
    Integer wholesaleQuotationId
) {}

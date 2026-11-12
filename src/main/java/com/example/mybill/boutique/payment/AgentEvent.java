package com.example.mybill.boutique.payment;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Sent to srisa-agent (POST {agent.events.url}/internal/events) after the DB change is committed. */
public record AgentEvent(String event, String orderNo, String customerPhone, String customerName, BigDecimal amount,
                         LocalDate estimatedDeliveryDate, String eventId, String shortUrl,
                         boolean exactMeasurementsRequired, String designCode, String designName,
                         /** GARMENT, FABRIC, READYMADE or MIXED. */
                         String orderType,
                         /** Goods orders: the lines and the delivery address, for the staff dispatch alert. */
                         String items, String deliveryAddress,
                         /** Firm schema the order belongs to (firm_srisa, firm_srihitha). */
                         String tenant,
                         /** DROPSHIP orders: who ships it (for the owner's "paid - order from …" message). */
                         String supplierName, String supplierPhone,
                         /** DROPSHIP orders: the owner number that forwarded the post (gets the "paid" message). */
                         String ownerPhone) {

    public AgentEvent(String event, String orderNo, String customerPhone, String customerName, java.math.BigDecimal amount,
                      java.time.LocalDate estimatedDeliveryDate, String eventId, String shortUrl,
                      boolean exactMeasurementsRequired, String designCode, String designName, String orderType,
                      String items, String deliveryAddress, String tenant, String supplierName, String supplierPhone) {
        this(event, orderNo, customerPhone, customerName, amount, estimatedDeliveryDate, eventId, shortUrl,
            exactMeasurementsRequired, designCode, designName, orderType, items, deliveryAddress, tenant, supplierName,
            supplierPhone, null);
    }

    public AgentEvent(String event, String orderNo, String customerPhone, String customerName, java.math.BigDecimal amount,
                      java.time.LocalDate estimatedDeliveryDate, String eventId, String shortUrl,
                      boolean exactMeasurementsRequired, String designCode, String designName, String orderType,
                      String items, String deliveryAddress, String tenant) {
        this(event, orderNo, customerPhone, customerName, amount, estimatedDeliveryDate, eventId, shortUrl,
            exactMeasurementsRequired, designCode, designName, orderType, items, deliveryAddress, tenant, null, null, null);
    }

    public static final String ORDER_PAID = "order.paid";
    public static final String LINK_EXPIRED = "payment_link.expired";
    public static final String LINK_CANCELLED = "payment_link.cancelled";
}

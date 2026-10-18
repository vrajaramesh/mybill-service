package com.example.mybill.boutique.payment;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Sent to srisa-agent (POST {agent.events.url}/internal/events) after the DB change is committed. */
public record AgentEvent(String event, String orderNo, String customerPhone, String customerName, BigDecimal amount,
                         LocalDate estimatedDeliveryDate, String eventId, String shortUrl) {

    public static final String ORDER_PAID = "order.paid";
    public static final String LINK_EXPIRED = "payment_link.expired";
    public static final String LINK_CANCELLED = "payment_link.cancelled";
}

package com.example.mybill.boutique.courier;

import com.example.mybill.boutique.payment.AgentEventNotifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.LinkedHashMap;
import java.util.Map;

/** A courier update for the customer (dispatched with AWB / out for delivery / delivered), sent to srisa-agent. */
public record CourierEvent(String event, String orderNo, String customerPhone, String customerName, String orderType,
                           String courier, String awb, String trackingUrl, String location, String eventId,
                           String tenant) {

    /** Posts after the DB change is committed, like the payment events (POST {agent.events.url}/internal/events). */
    @Component
    public static class Notifier {
        @Autowired private AgentEventNotifier notifier;

        @Async
        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        public void onCourierEvent(CourierEvent e) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("event", e.event());
            body.put("order_no", e.orderNo());
            body.put("customer_phone", e.customerPhone());
            body.put("customer_name", e.customerName());
            body.put("order_type", e.orderType());
            body.put("courier", e.courier());
            body.put("awb", e.awb());
            body.put("tracking_url", e.trackingUrl());
            if (e.location() != null) body.put("courier_location", e.location());
            body.put("event_id", e.eventId());
            body.put("tenant", e.tenant());
            notifier.post(body, e.event() + " " + e.orderNo());
        }
    }
}

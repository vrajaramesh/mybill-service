package com.example.mybill.boutique.service;

import com.example.mybill.boutique.payment.AgentEventNotifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sends {@link DropshipIntakeService.OwnerMessage}s to the owner's WhatsApp through srisa-agent (event
 * "owner.message" on /internal/events) after the DB commit, so a rolled-back step never messages the owner.
 */
@Component
public class OwnerMessageNotifier {

    @Autowired private AgentEventNotifier notifier;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onOwnerMessage(DropshipIntakeService.OwnerMessage m) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("event", "owner.message");
        body.put("event_id", m.eventId());
        body.put("order_no", "-");
        body.put("customer_phone", m.to());
        body.put("kind", m.kind());
        body.put("text", m.text());
        if (m.buttonText() != null) body.put("button_text", m.buttonText());
        if (m.sections() != null) body.put("sections", m.sections());
        notifier.post(body, "owner.message " + m.eventId());
    }
}

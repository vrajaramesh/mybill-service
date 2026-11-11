package com.example.mybill.boutique.service;

import com.example.mybill.boutique.exception.AgentApiException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * WhatsApp → Dropship, phase 6 (customer side, called by srisa-agent): before a customer can pay for a dropship
 * variant the owner confirms it is still available with the supplier (Yes / No buttons to every active owner
 * number); "No" marks the variant SOLD_OUT so it is never offered again.
 */
@Service
public class DropshipCustomerService {

    /** Button ids: avail:y:{conversationId}:{itemCode} / avail:n:… (handled by srisa-agent, which owns the chat). */
    public static final String AVAIL = "avail:";

    @PersistenceContext private EntityManager em;
    @Autowired private OwnerNumberService ownerNumbers;
    @Autowired private DropshipService dropship;
    @Autowired private ApplicationEventPublisher events;

    /** Ask the owner(s) "Is MGP1111-07 available with {supplier}?". Returns {asked: n}. */
    @Transactional(readOnly = true)
    public Map<String, Object> askAvailability(String itemCode, String conversationId, String customerName, String customerPhone) {
        Object[] row = itemRow(itemCode);
        String code = (String) row[0], colour = (String) row[1], collection = (String) row[2];
        DropshipService.Supplier s = row[3] == null ? null : dropship.supplier(((Number) row[3]).intValue()).orElse(null);
        String supplier = s == null ? "the supplier" : s.name() + (s.phone() == null ? "" : " (" + s.phone() + ")");
        String who = (customerName == null || customerName.isBlank() ? "A customer" : customerName)
            + (customerPhone == null || customerPhone.isBlank() ? "" : " (+" + customerPhone.replaceAll("\\D", "") + ")");
        String text = "🛒 " + who + " wants " + code + (colour == null ? "" : " – " + colour) + " (" + collection + ").\n"
            + "Is " + code + " available with " + supplier + "?";
        List<Map<String, String>> buttons = List.of(
            Map.of("id", AVAIL + "y:" + conversationId + ":" + code, "title", "✅ Yes"),
            Map.of("id", AVAIL + "n:" + conversationId + ":" + code, "title", "❌ No"));
        List<String> owners = ownerNumbers.activePhones();
        for (String owner : owners) {
            events.publishEvent(new DropshipIntakeService.OwnerMessage(owner, "avail:" + conversationId + ":" + code + ":" + owner
                + ":" + UUID.randomUUID(), "buttons", text, null, null, (String) row[4], null, buttons));
        }
        return Map.of("asked", owners.size(), "item_code", code);
    }

    /** The owner said it's gone: never offer it again. */
    @Transactional
    public Map<String, Object> markSoldOut(String itemCode) {
        int n = em.createNativeQuery("UPDATE boutique_collection_items SET status = 'SOLD_OUT' WHERE UPPER(item_code) = UPPER(?1)")
            .setParameter(1, itemCode.trim()).executeUpdate();
        if (n == 0) throw AgentApiException.notFound("ITEM_NOT_FOUND", "No collection item " + itemCode);
        return Map.of("item_code", itemCode.trim().toUpperCase(Locale.ROOT), "status", "SOLD_OUT");
    }

    /** {item_code, colour, collection code, supplier_id, photo} of a DROPSHIP item. */
    private Object[] itemRow(String itemCode) {
        List<?> rows = em.createNativeQuery("""
                SELECT i.item_code, i.colour, c.code, c.supplier_id, COALESCE(i.stamped_image_url, i.image_url), c.collection_type
                  FROM boutique_collection_items i JOIN boutique_collections c ON c.collection_id = i.collection_id
                 WHERE UPPER(i.item_code) = UPPER(?1)""").setParameter(1, itemCode == null ? "" : itemCode.trim()).getResultList();
        if (rows.isEmpty()) throw AgentApiException.notFound("ITEM_NOT_FOUND", "No collection item " + itemCode);
        Object[] r = (Object[]) rows.get(0);
        if (!CollectionService.DROPSHIP.equals(r[5])) {
            throw new AgentApiException(HttpStatus.UNPROCESSABLE_ENTITY, "NOT_DROPSHIP", itemCode + " is not a dropship item", null);
        }
        return r;
    }
}

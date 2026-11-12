package com.example.mybill.boutique.service;

import com.example.mybill.boutique.exception.AgentApiException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * WhatsApp → Dropship, phase 6 (customer side, called by srisa-agent): before a customer can pay for a dropship
 * variant, its availability is confirmed. First the SUPPLIER is asked directly (WhatsApp template with the original
 * photo and Yes / No buttons - suppliers haven't messaged the shop, so it must be a template); if the supplier hasn't
 * answered within 15 minutes (srisa-agent's delayed task calls {@link #askAvailability} with escalate) - or the
 * supplier has no number, or the template can't be sent - the owner who forwarded the post is asked instead (every
 * active owner number for a collection made by hand). "No" marks the variant SOLD_OUT so it is never offered again.
 */
@Service
public class DropshipCustomerService {

    /** Button ids: avail:y:{conversationId}:{itemCode} / avail:n:… (handled by srisa-agent, which owns the chat). */
    public static final String AVAIL = "avail:";

    @PersistenceContext private EntityManager em;
    @Autowired private OwnerNumberService ownerNumbers;
    @Autowired private DropshipService dropship;
    @Autowired private ApplicationEventPublisher events;

    @Value("${dropship.supplier-confirm.enabled:true}") private boolean supplierConfirm;

    /**
     * Sent to a supplier by srisa-agent: the approved template (photo header, body params, quick-reply payloads); if the
     * template can't be sent, fallbackText + buttons go to fallbackTo (the owner) instead.
     */
    public record SupplierCheck(String to, String eventId, String imageUrl, List<String> params, List<String> payloads,
                                String fallbackText, String fallbackImageUrl, List<Map<String, String>> fallbackButtons,
                                List<String> fallbackTo) {}

    /**
     * Ask whether a variant is available: the supplier first; escalate (supplier silent for 15 min) or no supplier
     * number = the collection's owner. Returns {asked, via: supplier|owner, item_code}.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> askAvailability(String itemCode, String conversationId, String customerName, String customerPhone,
                                               int quantity, boolean escalate) {
        int pieces = Math.max(quantity, 1);
        Object[] row = itemRow(itemCode);
        String code = (String) row[0], colour = (String) row[1], collection = (String) row[2];
        DropshipService.Supplier s = row[3] == null ? null : dropship.supplier(((Number) row[3]).intValue()).orElse(null);
        String supplier = s == null ? "the supplier" : s.name() + (s.phone() == null ? "" : " (" + s.phone() + ")");
        String who = (customerName == null || customerName.isBlank() ? "A customer" : customerName)
            + (customerPhone == null || customerPhone.isBlank() ? "" : " (+" + customerPhone.replaceAll("\\D", "") + ")");
        String text = (escalate && s != null ? "⏰ " + s.name() + " hasn't replied for 15 minutes.\n" : "")
            + "🛒 " + who + " wants " + code + (colour == null ? "" : " – " + colour) + " (" + collection + ")"
            + (pieces > 1 ? " × " + pieces + " pieces" : "") + ".\n"
            + "Is " + code + (pieces > 1 ? " (" + pieces + " pieces)" : "") + " available with " + supplier + "?";
        List<Map<String, String>> buttons = List.of(
            Map.of("id", AVAIL + "y:" + conversationId + ":" + code, "title", "✅ Yes"),
            Map.of("id", AVAIL + "n:" + conversationId + ":" + code, "title", "❌ No"));
        List<String> owners = ownerNumbers.recipientsFor(ownerNumbers.ownerOfItem(code));
        String supplierPhone = s == null ? null : waNumber(s.phone());
        if (!escalate && supplierConfirm && supplierPhone != null && !owners.contains(supplierPhone)) {
            String what = (row[6] == null ? "saree" : String.valueOf(row[6])) + (colour == null ? "" : " – " + colour);
            events.publishEvent(new SupplierCheck(supplierPhone, "avail-supplier:" + conversationId + ":" + code + ":" + UUID.randomUUID(),
                row[5] != null ? (String) row[5] : (String) row[4],
                List.of(s.name(), what, String.valueOf(pieces)),
                buttons.stream().map(b -> b.get("id")).toList(),
                text, (String) row[4], buttons, owners));
            return Map.of("asked", 1, "via", "supplier", "item_code", code, "supplier", s.name());
        }
        for (String owner : owners) {
            events.publishEvent(new DropshipIntakeService.OwnerMessage(owner, "avail:" + conversationId + ":" + code + ":" + owner
                + ":" + UUID.randomUUID(), "buttons", text, null, null, (String) row[4], null, buttons));
        }
        return Map.of("asked", owners.size(), "via", "owner", "item_code", code);
    }

    /**
     * Someone tapped Yes / No (srisa-agent already told the customer). A supplier's answer: thank them and tell the
     * collection's owner what happened. Returns {role: supplier|owner}.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> answered(String itemCode, int quantity, boolean available, String answeredBy, String customerName) {
        Object[] row = itemRow(itemCode);
        String code = (String) row[0];
        DropshipService.Supplier s = row[3] == null ? null : dropship.supplier(((Number) row[3]).intValue()).orElse(null);
        String by = waNumber(answeredBy);
        if (s == null || by == null || !by.equals(waNumber(s.phone()))) return Map.of("role", "owner");
        String what = code + (quantity > 1 ? " × " + quantity : "");
        events.publishEvent(new DropshipIntakeService.OwnerMessage(by, "avail-thanks:" + code + ":" + UUID.randomUUID(), "text",
            available ? "🙏 Thank you! We'll send you the order and delivery address as soon as the customer pays."
                      : "🙏 Thank you for letting us know.", null, null, null, null));
        String customer = customerName == null || customerName.isBlank() ? "a customer" : customerName;
        String text = available
            ? "✅ " + s.name() + " confirmed " + what + " for " + customer + " – the customer has been told it's available."
            : "❌ " + s.name() + " says " + what + " is not available – marked SOLD OUT; the customer was told and offered other colours.";
        for (String owner : ownerNumbers.recipientsFor(ownerNumbers.ownerOfItem(code))) {
            events.publishEvent(new DropshipIntakeService.OwnerMessage(owner, "avail-result:" + code + ":" + owner + ":" + UUID.randomUUID(),
                "text", text, null, null, null, null));
        }
        return Map.of("role", "supplier");
    }

    /** WhatsApp numbers of active suppliers (91XXXXXXXXXX): srisa-agent never lets the AI answer them. */
    @Transactional(readOnly = true)
    public List<String> supplierNumbers() {
        return dropship.suppliers().stream().filter(DropshipService.Supplier::active)
            .map(x -> waNumber(x.phone())).filter(Objects::nonNull).distinct().toList();
    }

    /** A supplier wrote something other than a Yes / No tap: forward it to the owner of their latest collection. */
    @Transactional(readOnly = true)
    public Map<String, Object> supplierMessage(String from, String text) {
        String phone = waNumber(from);
        DropshipService.Supplier s = phone == null ? null : dropship.suppliers().stream()
            .filter(x -> phone.equals(waNumber(x.phone()))).findFirst().orElse(null);
        if (s == null) return Map.of("forwarded", 0);
        List<?> latest = em.createNativeQuery("""
                SELECT collection_id FROM boutique_collections WHERE supplier_id = ?1 ORDER BY created_at DESC LIMIT 1""")
            .setParameter(1, s.id()).getResultList();
        Optional<String> owner = latest.isEmpty() ? Optional.empty() : ownerNumbers.ownerOfCollection(((Number) latest.get(0)).intValue());
        List<String> to = ownerNumbers.recipientsFor(owner);
        String body = "📩 Supplier " + s.name() + " (+" + phone + ") wrote:\n" + (text == null || text.isBlank() ? "[a photo or file]" : text);
        for (String o : to) {
            events.publishEvent(new DropshipIntakeService.OwnerMessage(o, "supplier-msg:" + phone + ":" + o + ":" + UUID.randomUUID(),
                "text", body.length() > 1000 ? body.substring(0, 1000) : body, null, null, null, null));
        }
        return Map.of("forwarded", to.size(), "supplier", s.name());
    }

    /** The owner said it's gone: never offer it again. */
    @Transactional
    public Map<String, Object> markSoldOut(String itemCode) {
        int n = em.createNativeQuery("UPDATE boutique_collection_items SET status = 'SOLD_OUT' WHERE UPPER(item_code) = UPPER(?1)")
            .setParameter(1, itemCode.trim()).executeUpdate();
        if (n == 0) throw AgentApiException.notFound("ITEM_NOT_FOUND", "No collection item " + itemCode);
        return Map.of("item_code", itemCode.trim().toUpperCase(Locale.ROOT), "status", "SOLD_OUT");
    }

    /** 10-digit or with country code → 91XXXXXXXXXX; null if it isn't a phone number. */
    static String waNumber(String raw) {
        String d = raw == null ? "" : raw.replaceAll("\\D", "");
        if (d.length() == 11 && d.startsWith("0")) d = d.substring(1);
        if (d.length() == 10) return "91" + d;
        return d.length() >= 11 && d.length() <= 15 ? d : null;
    }

    /** {item_code, colour, collection code, supplier_id, photo (stamped), original photo, collection name} of a DROPSHIP item. */
    private Object[] itemRow(String itemCode) {
        List<?> rows = em.createNativeQuery("""
                SELECT i.item_code, i.colour, c.code, c.supplier_id, COALESCE(i.stamped_image_url, i.image_url),
                       i.original_image_path, c.name, c.collection_type
                  FROM boutique_collection_items i JOIN boutique_collections c ON c.collection_id = i.collection_id
                 WHERE UPPER(i.item_code) = UPPER(?1)""").setParameter(1, itemCode == null ? "" : itemCode.trim()).getResultList();
        if (rows.isEmpty()) throw AgentApiException.notFound("ITEM_NOT_FOUND", "No collection item " + itemCode);
        Object[] r = (Object[]) rows.get(0);
        if (!CollectionService.DROPSHIP.equals(r[7])) {
            throw new AgentApiException(HttpStatus.UNPROCESSABLE_ENTITY, "NOT_DROPSHIP", itemCode + " is not a dropship item", null);
        }
        return r;
    }
}

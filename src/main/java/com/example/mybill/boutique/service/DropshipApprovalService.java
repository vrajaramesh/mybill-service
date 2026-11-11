package com.example.mybill.boutique.service;

import com.example.mybill.boutique.entity.WaIntakeBatch;
import com.example.mybill.boutique.exception.AgentApiException;
import com.example.mybill.boutique.repository.WaIntakeBatchRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Approving / archiving a dropship collection and editing its variants - from mybill-ui (Collections) and, with the
 * same effect, from the owner's WhatsApp buttons. Approve = ACTIVE (customers can buy it; its post, if it came from a
 * WhatsApp forward, becomes APPROVED - publishing to Instagram / the Meta catalog hooks in here). Archive = ARCHIVED.
 */
@Service
public class DropshipApprovalService {

    @PersistenceContext private EntityManager em;
    @Autowired private CollectionService collections;
    @Autowired private WaIntakeBatchRepository batches;
    @Autowired private ApplicationEventPublisher events;

    @Transactional
    public Map<String, Object> approve(int collectionId, String by) {
        Map<String, Object> c = requireDropship(collectionId);
        if (((List<?>) c.get("items")).isEmpty()) throw bad("Add at least one variant before approving");
        setStatus(collectionId, "ACTIVE");
        batch(collectionId).ifPresent(b -> {
            if (WaIntakeBatch.AWAITING_APPROVAL.equals(b.getStatus())) {
                b.setStatus(WaIntakeBatch.APPROVED);
                tell(b, "✅ " + c.get("code") + " approved" + (by == null ? "" : " by " + by) + " – customers can now see and buy it.");
            }
        });
        return collections.get(String.valueOf(collectionId), true);
    }

    @Transactional
    public Map<String, Object> archive(int collectionId, String by) {
        Map<String, Object> c = requireDropship(collectionId);
        setStatus(collectionId, "ARCHIVED");
        batch(collectionId).ifPresent(b -> {
            if (WaIntakeBatch.AWAITING_APPROVAL.equals(b.getStatus())) {
                b.setStatus(WaIntakeBatch.SKIPPED);
                tell(b, "🗄️ " + c.get("code") + " archived" + (by == null ? "" : " by " + by) + " – not offered to customers.");
            }
        });
        return collections.get(String.valueOf(collectionId), true);
    }

    /** One variant: status AVAILABLE / SOLD_OUT and/or its selling price. */
    @Transactional
    public Map<String, Object> updateVariant(int collectionId, String itemCode, Map<String, Object> body) {
        requireDropship(collectionId);
        List<?> rows = em.createNativeQuery("""
                SELECT product_id FROM boutique_collection_items WHERE collection_id = ?1 AND UPPER(item_code) = UPPER(?2)""")
            .setParameter(1, collectionId).setParameter(2, itemCode).getResultList();
        if (rows.isEmpty()) throw AgentApiException.notFound("ITEM_NOT_FOUND", "No variant " + itemCode + " in this collection");
        if (body.get("status") != null) {
            String status = String.valueOf(body.get("status")).toUpperCase(Locale.ROOT);
            if (!CollectionService.AVAILABLE.equals(status) && !CollectionService.SOLD_OUT.equals(status)) {
                throw bad("Status must be AVAILABLE or SOLD_OUT");
            }
            em.createNativeQuery("UPDATE boutique_collection_items SET status = ?1 WHERE collection_id = ?2 AND UPPER(item_code) = UPPER(?3)")
                .setParameter(1, status).setParameter(2, collectionId).setParameter(3, itemCode).executeUpdate();
        }
        if (body.get("price") != null && rows.get(0) != null) {
            setPrice(List.of(((Number) rows.get(0)).intValue()), price(body.get("price")));
        }
        return collections.get(String.valueOf(collectionId), true);
    }

    /** The same selling price for every variant ("Edit price"). */
    @Transactional
    public Map<String, Object> setPriceForAll(int collectionId, Object rawPrice) {
        requireDropship(collectionId);
        BigDecimal price = price(rawPrice);
        @SuppressWarnings("unchecked")
        List<Object> ids = em.createNativeQuery("SELECT product_id FROM boutique_collection_items WHERE collection_id = ?1 AND product_id IS NOT NULL")
            .setParameter(1, collectionId).getResultList();
        setPrice(ids.stream().map(o -> ((Number) o).intValue()).toList(), price);
        return collections.get(String.valueOf(collectionId), true);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void setPrice(List<Integer> productIds, BigDecimal price) {
        if (productIds.isEmpty()) return;
        em.createNativeQuery("UPDATE products SET selling_price = ?1, updated_at = NOW() WHERE product_id IN (?2)")
            .setParameter(1, price).setParameter(2, productIds).executeUpdate();
    }

    private void setStatus(int collectionId, String status) {
        em.createNativeQuery("UPDATE boutique_collections SET status = ?1, is_active = ?2, updated_at = NOW() WHERE collection_id = ?3")
            .setParameter(1, status).setParameter(2, "ACTIVE".equals(status)).setParameter(3, collectionId).executeUpdate();
    }

    private Map<String, Object> requireDropship(int collectionId) {
        Map<String, Object> c = collections.get(String.valueOf(collectionId), true);
        if (!CollectionService.DROPSHIP.equals(c.get("type"))) throw bad("Only dropship collections can be approved or archived here");
        return c;
    }

    private Optional<WaIntakeBatch> batch(int collectionId) {
        @SuppressWarnings("unchecked")
        List<Object> ids = em.createNativeQuery("SELECT batch_id FROM wa_intake_batch WHERE collection_id = ?1 ORDER BY batch_id DESC LIMIT 1")
            .setParameter(1, collectionId).getResultList();
        return ids.isEmpty() ? Optional.empty() : batches.findById(((Number) ids.get(0)).intValue());
    }

    private void tell(WaIntakeBatch b, String text) {
        events.publishEvent(new DropshipIntakeService.OwnerMessage(b.getFromNumber(), "dropship:" + b.getBatchId() + ":" + UUID.randomUUID(),
            "text", text, null, null, null, null));
    }

    private static BigDecimal price(Object raw) {
        try {
            BigDecimal p = new BigDecimal(String.valueOf(raw).trim()).setScale(2, RoundingMode.HALF_UP);
            if (p.signum() <= 0) throw bad("Price must be more than 0");
            return p;
        } catch (NumberFormatException e) {
            throw bad("Price must be a number");
        }
    }

    private static AgentApiException bad(String message) {
        return new AgentApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message, null);
    }
}

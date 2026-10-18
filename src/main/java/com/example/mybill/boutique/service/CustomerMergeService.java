package com.example.mybill.boutique.service;

import com.example.mybill.boutique.exception.AgentApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Merges two customers that turn out to be one person (the agent saw her phone and Instagram id together).
 * The older customer (lower id) is kept; everything pointing at the newer one is moved to it, the newer row is
 * deleted, and the merge is recorded in customer_merges with a copy of the deleted row.
 * Runs inside the caller's transaction (the upsert), so a failure leaves both customers untouched.
 */
@Service
public class CustomerMergeService {

    private static final Logger log = LoggerFactory.getLogger(CustomerMergeService.class);

    /** Every table in a firm schema with a customer_id that references customers (ecom_* use ecom_customers). */
    static final List<String> REFERENCING_TABLES =
        List.of("customer_measurements", "boutique_orders", "stitching_orders", "bills", "marketing_events");

    @PersistenceContext private EntityManager em;
    @Autowired private ObjectMapper objectMapper;

    public record MergeResult(int keptCustomerId, int mergedCustomerId, Map<String, Integer> moved) {}

    /**
     * @param phoneCustomerId     the customer that has the phone number
     * @param instagramCustomerId the customer that has the Instagram id
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public MergeResult merge(int phoneCustomerId, int instagramCustomerId, String phoneE164, String instagramUserId) {
        int kept = Math.min(phoneCustomerId, instagramCustomerId);
        int merged = Math.max(phoneCustomerId, instagramCustomerId);
        em.flush();

        // Lock both rows (in id order, so two concurrent merges can't deadlock) and read their identities.
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT customer_id, phone_e164, instagram_user_id FROM customers
                 WHERE customer_id IN (?1, ?2) ORDER BY customer_id FOR UPDATE""")
            .setParameter(1, kept).setParameter(2, merged).getResultList();
        if (rows.size() != 2) {
            throw AgentApiException.conflict("CUSTOMER_MERGE_FAILED", "One of the customers to merge no longer exists");
        }
        // Each side may only bring identities that don't contradict the other side.
        for (Object[] r : rows) {
            String phone = (String) r[1], ig = (String) r[2];
            if (phone != null && !phone.equals(phoneE164) || ig != null && !ig.equals(instagramUserId)) {
                throw new AgentApiException(org.springframework.http.HttpStatus.CONFLICT, "IDENTITY_CONFLICT",
                    "The phone number and Instagram account belong to customers with other conflicting details",
                    Map.of("customer_ids", List.of(kept, merged)));
            }
        }

        Map<String, Integer> moved = new LinkedHashMap<>();
        for (String table : REFERENCING_TABLES) {
            if (!tableExists(table)) continue;
            int n = em.createNativeQuery("UPDATE " + table + " SET customer_id = ?1 WHERE customer_id = ?2")
                .setParameter(1, kept).setParameter(2, merged).executeUpdate();
            moved.put(table, n);
        }

        String snapshot = (String) em.createNativeQuery(
                "SELECT CAST(row_to_json(c) AS text) FROM customers c WHERE customer_id = ?1")
            .setParameter(1, merged).getSingleResult();
        em.createNativeQuery("DELETE FROM customers WHERE customer_id = ?1").setParameter(1, merged).executeUpdate();

        // Kept customer gets the identities (now free) and fills empty details from the merged one.
        em.createNativeQuery("""
                UPDATE customers k
                   SET phone_e164        = ?2,
                       instagram_user_id = ?3,
                       customer_name     = COALESCE(NULLIF(k.customer_name, ''), m.customer_name),
                       phone             = COALESCE(NULLIF(k.phone, ''), m.phone),
                       whatsapp_updates_consent = COALESCE(k.whatsapp_updates_consent, m.whatsapp_updates_consent),
                       consent_at        = CASE WHEN k.whatsapp_updates_consent IS NULL THEN m.consent_at ELSE k.consent_at END,
                       updated_at        = NOW()
                  FROM json_populate_record(CAST(NULL AS customers), CAST(?4 AS json)) m
                 WHERE k.customer_id = ?1""")
            .setParameter(1, kept).setParameter(2, phoneE164).setParameter(3, instagramUserId)
            .setParameter(4, snapshot).executeUpdate();

        em.createNativeQuery("""
                INSERT INTO customer_merges (kept_customer_id, merged_customer_id, phone_e164, instagram_user_id,
                                             moved, merged_customer, reason)
                VALUES (?1, ?2, ?3, ?4, CAST(?5 AS jsonb), CAST(?6 AS jsonb), 'AGENT_UPSERT_PHONE_AND_INSTAGRAM')""")
            .setParameter(1, kept).setParameter(2, merged).setParameter(3, phoneE164).setParameter(4, instagramUserId)
            .setParameter(5, toJson(moved)).setParameter(6, snapshot).executeUpdate();

        em.clear();   // entities loaded before the merge are stale now
        log.info("CUSTOMER_MERGED kept={} merged={} moved={}", kept, merged, moved);
        return new MergeResult(kept, merged, moved);
    }

    private boolean tableExists(String table) {
        return !em.createNativeQuery("SELECT 1 FROM information_schema.tables "
                + "WHERE table_schema = current_schema() AND table_name = ?1")
            .setParameter(1, table).getResultList().isEmpty();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}

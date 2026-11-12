package com.example.mybill.boutique.service;

import com.example.mybill.boutique.exception.AgentApiException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.query.NativeQuery;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Owner numbers (V20): WhatsApp numbers whose forwarded supplier posts become dropship collections. Managed in
 * mybill-ui; srisa-agent reads the active ones through /api/agent/dropship/owner-numbers.
 */
@Service
public class OwnerNumberService {

    @PersistenceContext private EntityManager em;

    public record OwnerNumber(String phone, String label, boolean active) {}

    @Transactional(readOnly = true)
    public List<OwnerNumber> list() {
        List<OwnerNumber> out = new ArrayList<>();
        for (Object r : em.createNativeQuery("SELECT phone, label, is_active FROM dropship_owner_numbers ORDER BY is_active DESC, created_at")
                .getResultList()) {
            Object[] a = (Object[]) r;
            out.add(new OwnerNumber((String) a[0], (String) a[1], !Boolean.FALSE.equals(a[2])));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<String> activePhones() {
        return list().stream().filter(OwnerNumber::active).map(OwnerNumber::phone).toList();
    }

    /**
     * The owner who forwarded a dropship collection's post (V21 owner_phone, else its intake batch's sender); empty for
     * a collection made by hand in mybill. Each owner is their own user: questions about the collection go only to them.
     */
    @Transactional(readOnly = true)
    public Optional<String> ownerOfCollection(int collectionId) {
        List<?> r = em.createNativeQuery("""
                SELECT COALESCE(c.owner_phone, (SELECT b.from_number FROM wa_intake_batch b WHERE b.collection_id = c.collection_id
                                                 ORDER BY b.batch_id DESC LIMIT 1))
                  FROM boutique_collections c WHERE c.collection_id = ?1""").setParameter(1, collectionId).getResultList();
        return r.isEmpty() || r.get(0) == null ? Optional.empty() : Optional.of(String.valueOf(r.get(0)));
    }

    /** ownerOfCollection for the collection of an item code (e.g. KSS1211-03). */
    @Transactional(readOnly = true)
    public Optional<String> ownerOfItem(String itemCode) {
        List<?> r = em.createNativeQuery("SELECT collection_id FROM boutique_collection_items WHERE UPPER(item_code) = UPPER(?1)")
            .setParameter(1, itemCode == null ? "" : itemCode.trim()).getResultList();
        return r.isEmpty() ? Optional.empty() : ownerOfCollection(((Number) r.get(0)).intValue());
    }

    /** Who to ask about a collection: its owner, or every active owner number when it has none (made by hand). */
    @Transactional(readOnly = true)
    public List<String> recipientsFor(Optional<String> owner) {
        return owner.map(List::of).orElseGet(this::activePhones);
    }

    /** Add or update (phone in any format: 9441249575, +91 94412 49575, 919441249575). */
    @Transactional
    public OwnerNumber save(Map<String, Object> body) {
        String phone = normalise(String.valueOf(body.getOrDefault("phone", "")));
        String label = body.get("label") == null ? null : String.valueOf(body.get("label")).trim();
        if (label != null && label.isEmpty()) label = null;
        if (label != null && label.length() > 60) label = label.substring(0, 60);
        boolean active = !(body.get("active") instanceof Boolean b) || b;
        em.createNativeQuery("""
                INSERT INTO dropship_owner_numbers (phone, label, is_active) VALUES (?1, ?2, ?3)
                ON CONFLICT (phone) DO UPDATE SET label = EXCLUDED.label, is_active = EXCLUDED.is_active, updated_at = NOW()""")
            .unwrap(NativeQuery.class)
            .setParameter(1, phone, String.class).setParameter(2, label, String.class).setParameter(3, active, Boolean.class)
            .executeUpdate();
        return new OwnerNumber(phone, label, active);
    }

    @Transactional
    public void delete(String phone) {
        em.createNativeQuery("DELETE FROM dropship_owner_numbers WHERE phone = ?1").setParameter(1, normalise(phone)).executeUpdate();
    }

    static String normalise(String raw) {
        String d = raw == null ? "" : raw.replaceAll("\\D", "");
        if (d.length() == 11 && d.startsWith("0")) d = d.substring(1);
        if (d.length() == 10) d = "91" + d;
        if (d.length() < 11 || d.length() > 15) {
            throw new AgentApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                "Enter a WhatsApp number: 10 digits, or with the country code (e.g. 919441249575)", null);
        }
        return d;
    }
}

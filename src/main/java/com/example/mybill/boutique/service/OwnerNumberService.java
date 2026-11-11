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

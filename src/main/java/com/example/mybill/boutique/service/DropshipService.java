package com.example.mybill.boutique.service;

import com.example.mybill.boutique.entity.BoutiqueOrder;
import com.example.mybill.boutique.entity.BoutiqueOrderItem;
import com.example.mybill.boutique.entity.BoutiqueOrderStatus;
import com.example.mybill.boutique.exception.AgentApiException;
import com.example.mybill.boutique.repository.BoutiqueOrderRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.query.NativeQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Dropshipping (db/boutique/V16): suppliers, and the courier charge of a supplier's products.
 *
 * The supplier books their own courier and charges by distance; we pass that charge on as the delivery charge.
 * Distance = the zone from the supplier's pincode to the customer's (Indian pincodes are hierarchical):
 *   LOCAL    same first 3 digits (same sorting district / city)
 *   STATE    same first 2 digits (same postal circle, roughly the state)
 *   REGION   same first digit (same postal region, e.g. 5xxxxx = Telangana, AP, Karnataka)
 *   NATIONAL anywhere else
 *   SPECIAL  J&K / Ladakh (18, 19), North East (78, 79), Sikkim (737), Andaman (744), Lakshadweep (6825)
 * Each DROPSHIP collection has a charge per zone (an unset zone uses the next farther one that is set) and an
 * optional charge per extra piece. After payment staff send the order to the supplier on WhatsApp, pay them per order
 * (cost price x qty + their courier charge) and enter the tracking number they give (CourierService.shipManually).
 */
@Service
public class DropshipService {

    private static final Logger log = LoggerFactory.getLogger(DropshipService.class);
    public static final List<String> ZONES = List.of("LOCAL", "STATE", "REGION", "NATIONAL", "SPECIAL");
    private static final List<String> SPECIAL_PREFIXES = List.of("18", "19", "78", "79", "737", "744", "6825");

    @PersistenceContext private EntityManager em;
    @Autowired private ObjectMapper json;
    @Autowired private BoutiqueOrderRepository orderRepository;

    /** defaultCourierSlabs / defaultCourierExtraPerPiece: copied into collections built from their forwarded posts (V18). */
    public record Supplier(int id, String name, String contactPerson, String phone, String address, String pincode,
                           String paymentDetails, int dispatchDays, String notes, boolean active,
                           Map<String, BigDecimal> defaultCourierSlabs, BigDecimal defaultCourierExtraPerPiece,
                           /** Offered in the WhatsApp "Which supplier?" list (V19); lastUsedAt orders that list. */
                           boolean dropshipActive, java.time.LocalDateTime lastUsedAt) {}

    /** How a DROPSHIP collection charges courier. */
    public record Slabs(Map<String, BigDecimal> perZone, BigDecimal extraPerPiece) {}

    // ── zones and charges ─────────────────────────────────────────────────

    public static String zone(String fromPincode, String toPincode) {
        String to = digits(toPincode), from = digits(fromPincode);
        if (to.length() != 6) return null;
        if (SPECIAL_PREFIXES.stream().anyMatch(to::startsWith)) return "SPECIAL";
        if (from.length() != 6) return "NATIONAL";
        if (from.regionMatches(0, to, 0, 3)) return "LOCAL";
        if (from.regionMatches(0, to, 0, 2)) return "STATE";
        if (from.charAt(0) == to.charAt(0)) return "REGION";
        return "NATIONAL";
    }

    /** Charge for the zone: that zone's slab, else the next farther zone that is set (empty if none). */
    public static Optional<BigDecimal> slabFor(Slabs slabs, String zone) {
        int start = zone == null ? ZONES.indexOf("NATIONAL") : Math.max(ZONES.indexOf(zone), 0);
        for (int i = start; i < ZONES.size(); i++) {
            BigDecimal v = slabs.perZone().get(ZONES.get(i));
            if (v != null) return Optional.of(v);
        }
        for (int i = start - 1; i >= 0; i--) {             // only nearer zones set: use the farthest of those
            BigDecimal v = slabs.perZone().get(ZONES.get(i));
            if (v != null) return Optional.of(v);
        }
        return Optional.empty();
    }

    public Slabs parseSlabs(String slabsJson, BigDecimal extraPerPiece) {
        Map<String, BigDecimal> perZone = new LinkedHashMap<>();
        if (slabsJson != null && !slabsJson.isBlank()) {
            try {
                Map<String, Object> raw = json.readValue(slabsJson, new TypeReference<>() {});
                for (String z : ZONES) {
                    Object v = raw.get(z);
                    if (v != null && !v.toString().isBlank()) perZone.put(z, PricingService.money(new BigDecimal(v.toString())));
                }
            } catch (Exception e) {
                log.warn("Bad courier_slabs JSON: {}", slabsJson);
            }
        }
        return new Slabs(perZone, extraPerPiece == null ? BigDecimal.ZERO : extraPerPiece);
    }

    /** Validated slabs JSON from the UI body {"LOCAL": 60, ...}; null when nothing is set. */
    public String slabsJson(Object body) {
        if (!(body instanceof Map<?, ?> m)) return null;
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (String z : ZONES) {
            Object v = m.get(z);
            if (v == null || v.toString().isBlank()) continue;
            try {
                BigDecimal d = new BigDecimal(v.toString().trim());
                if (d.signum() < 0) throw bad("Courier charge for " + z + " can't be negative");
                out.put(z, PricingService.money(d));
            } catch (NumberFormatException e) {
                throw bad("Courier charge for " + z + " must be a number");
            }
        }
        if (out.isEmpty()) return null;
        try {
            return json.writeValueAsString(out);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ── suppliers ─────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Supplier> suppliers() {
        List<Supplier> out = new ArrayList<>();
        for (Object r : em.createNativeQuery("""
                SELECT supplier_id, name, contact_person, phone, address, pincode, payment_details, dispatch_days, notes, is_active,
                       default_courier_slabs, default_courier_extra_per_piece, dropship_active, last_used_at
                  FROM boutique_suppliers ORDER BY is_active DESC, name""").getResultList()) {
            out.add(supplier((Object[]) r));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Optional<Supplier> supplier(Integer id) {
        if (id == null) return Optional.empty();
        List<?> rows = em.createNativeQuery("""
                SELECT supplier_id, name, contact_person, phone, address, pincode, payment_details, dispatch_days, notes, is_active,
                       default_courier_slabs, default_courier_extra_per_piece, dropship_active, last_used_at
                  FROM boutique_suppliers WHERE supplier_id = ?1""").setParameter(1, id).getResultList();
        return rows.isEmpty() ? Optional.empty() : Optional.of(supplier((Object[]) rows.get(0)));
    }

    /** Create (id null) or update. Body: name, contact_person, phone, address, pincode, payment_details, dispatch_days, notes, active. */
    @Transactional
    public Supplier saveSupplier(Integer id, Map<String, Object> body) {
        String name = str(body.get("name"));
        if (name.isEmpty()) throw bad("Supplier name is required");
        String phone = digits(str(body.get("phone")));
        if (!phone.isEmpty() && phone.length() < 10) throw bad("Phone must have 10 digits (or +91 and 10 digits)");
        String pincode = digits(str(body.get("pincode")));
        if (!pincode.isEmpty() && pincode.length() != 6) throw bad("Pincode must be 6 digits");
        int dispatchDays = 2;
        if (body.get("dispatch_days") != null && !str(body.get("dispatch_days")).isEmpty()) {
            try {
                dispatchDays = Integer.parseInt(str(body.get("dispatch_days")));
            } catch (NumberFormatException e) {
                throw bad("Dispatch days must be a whole number");
            }
            if (dispatchDays < 0 || dispatchDays > 30) throw bad("Dispatch days must be 0-30");
        }
        boolean active = !(body.get("active") instanceof Boolean b) || b;
        NativeQuery<?> q;
        if (id == null) {
            q = em.createNativeQuery("""
                    INSERT INTO boutique_suppliers (name, contact_person, phone, address, pincode, payment_details, dispatch_days, notes, is_active,
                                                    default_courier_slabs, default_courier_extra_per_piece, dropship_active)
                    VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12) RETURNING supplier_id""").unwrap(NativeQuery.class);
        } else {
            supplier(id).orElseThrow(() -> AgentApiException.notFound("SUPPLIER_NOT_FOUND", "No supplier " + id));
            q = em.createNativeQuery("""
                    UPDATE boutique_suppliers SET name = ?1, contact_person = ?2, phone = ?3, address = ?4, pincode = ?5,
                           payment_details = ?6, dispatch_days = ?7, notes = ?8, is_active = ?9, default_courier_slabs = ?10,
                           default_courier_extra_per_piece = ?11, dropship_active = ?12, updated_at = NOW()
                     WHERE supplier_id = ?13 RETURNING supplier_id""").unwrap(NativeQuery.class);
            q.setParameter(13, id, Integer.class);
        }
        q.setParameter(1, name, String.class)
            .setParameter(2, nullable(body.get("contact_person")), String.class)
            .setParameter(3, phone.isEmpty() ? null : phone, String.class)
            .setParameter(4, nullable(body.get("address")), String.class)
            .setParameter(5, pincode.isEmpty() ? null : pincode, String.class)
            .setParameter(6, nullable(body.get("payment_details")), String.class)
            .setParameter(7, dispatchDays, Integer.class)
            .setParameter(8, nullable(body.get("notes")), String.class)
            .setParameter(9, active, Boolean.class)
            .setParameter(10, slabsJson(body.get("default_courier_slabs")), String.class)
            .setParameter(11, money(body.get("default_courier_extra_per_piece"), "Extra courier charge per piece"), BigDecimal.class)
            .setParameter(12, !(body.get("dropship_active") instanceof Boolean da) || da, Boolean.class);
        int saved = ((Number) q.getSingleResult()).intValue();
        return supplier(saved).orElseThrow();
    }

    /** Active suppliers offered for a forwarded post: active and dropship_active, most recently used first. */
    @Transactional(readOnly = true)
    public List<Supplier> dropshipChoices() {
        return suppliers().stream().filter(s -> s.active() && s.dropshipActive())
            .sorted(Comparator.comparing(Supplier::lastUsedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Supplier::name, String.CASE_INSENSITIVE_ORDER))
            .toList();
    }

    @Transactional
    public void markUsed(int supplierId) {
        em.createNativeQuery("UPDATE boutique_suppliers SET last_used_at = NOW() WHERE supplier_id = ?1")
            .setParameter(1, supplierId).executeUpdate();
    }

    @Transactional
    public void deleteSupplier(int id) {
        Object used = em.createNativeQuery("SELECT COUNT(*) FROM boutique_collections WHERE supplier_id = ?1")
            .setParameter(1, id).getSingleResult();
        if (((Number) used).intValue() > 0) {
            throw new AgentApiException(HttpStatus.CONFLICT, "SUPPLIER_IN_USE",
                "This supplier has products (collections) - move them to another supplier or mark the supplier inactive", null);
        }
        em.createNativeQuery("DELETE FROM boutique_suppliers WHERE supplier_id = ?1").setParameter(1, id).executeUpdate();
    }

    // ── a paid dropship order: send to the supplier, pay them ─────────────

    /** The supplier side of a dropship order for staff: who, what we owe, the WhatsApp message to send them. */
    @Transactional(readOnly = true)
    public Map<String, Object> orderView(String orderNo) {
        BoutiqueOrder order = dropshipOrder(orderNo);
        Optional<Supplier> s = supplier(order.getSupplierId());
        BigDecimal cost = nz(order.getSupplierCost()), courier = nz(order.getSupplierCourier());
        BigDecimal payable = cost.add(courier);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("order_no", order.getOrderNo());
        out.put("supplier_id", order.getSupplierId());
        out.put("supplier_name", s.map(Supplier::name).orElse(order.getSupplierName()));
        out.put("supplier_phone", s.map(Supplier::phone).orElse(null));
        out.put("supplier_payment_details", s.map(Supplier::paymentDetails).orElse(null));
        out.put("zone", order.getSupplierZone());
        out.put("cost", cost);
        out.put("courier", courier);
        out.put("payable", payable);
        out.put("customer_paid", order.getTotal());
        out.put("margin", nz(order.getTotal()).subtract(payable));
        out.put("sent_at", order.getSupplierSentAt());
        out.put("paid_at", order.getSupplierPaidAt());
        out.put("paid_ref", order.getSupplierPaidRef());
        out.put("paid_by", order.getSupplierPaidBy());
        String message = supplierMessage(order, cost, courier, payable);
        out.put("message", message);
        String phone = s.map(Supplier::phone).map(DropshipService::waNumber).orElse(null);
        out.put("whatsapp_url", phone == null ? null
            : "https://wa.me/" + phone + "?text=" + URLEncoder.encode(message, StandardCharsets.UTF_8).replace("+", "%20"));
        return out;
    }

    @Transactional
    public Map<String, Object> markSent(String orderNo, String staff) {
        BoutiqueOrder order = dropshipOrder(orderNo);
        requirePaid(order);
        if (order.getSupplierSentAt() == null) {
            order.setSupplierSentAt(LocalDateTime.now());
            order.setUpdatedAt(LocalDateTime.now());
            log.info("DROPSHIP_SENT_TO_SUPPLIER order={} supplier={} by={}", order.getOrderNo(), order.getSupplierId(), staff);
        }
        return orderView(orderNo);
    }

    /** Staff paid the supplier for this order (UPI / bank reference); setting it again corrects the reference. */
    @Transactional
    public Map<String, Object> markPaid(String orderNo, String reference, String staff) {
        BoutiqueOrder order = dropshipOrder(orderNo);
        requirePaid(order);
        String ref = reference == null ? "" : reference.trim();
        if (ref.length() > 100) ref = ref.substring(0, 100);
        if (order.getSupplierPaidAt() == null) order.setSupplierPaidAt(LocalDateTime.now());
        order.setSupplierPaidRef(ref.isEmpty() ? null : ref);
        order.setSupplierPaidBy(staff);
        order.setUpdatedAt(LocalDateTime.now());
        log.info("DROPSHIP_SUPPLIER_PAID order={} supplier={} amount={} ref={} by={}", order.getOrderNo(), order.getSupplierId(),
            nz(order.getSupplierCost()).add(nz(order.getSupplierCourier())), ref, staff);
        return orderView(orderNo);
    }

    private String supplierMessage(BoutiqueOrder o, BigDecimal cost, BigDecimal courier, BigDecimal payable) {
        StringBuilder sb = new StringBuilder("New order from SRISA – ").append(o.getOrderNo()).append("\n\n");
        int n = 1;
        for (BoutiqueOrderItem i : o.getItems()) {
            sb.append(n++).append(". ").append(i.getDesignCode()).append(" ").append(i.getDesignName());
            if (i.getSizeCode() != null && !"-".equals(i.getSizeCode())) sb.append(", size ").append(i.getSizeCode());
            sb.append(" × ").append(i.getQuantity()).append("\n");
        }
        sb.append("\nShip to:\n").append(o.getDeliveryName()).append("\n").append(o.getDeliveryAddressLine1()).append("\n");
        if (o.getDeliveryAddressLine2() != null && !o.getDeliveryAddressLine2().isBlank()) sb.append(o.getDeliveryAddressLine2()).append("\n");
        sb.append(o.getDeliveryCity()).append(", ").append(o.getDeliveryState()).append(" – ").append(o.getDeliveryPincode()).append("\n")
            .append("Phone: ").append(o.getDeliveryPhone()).append("\n\n")
            .append("Amount: ").append(rupees(cost)).append(" + courier ").append(rupees(courier)).append(" = ").append(rupees(payable)).append("\n")
            .append("Prepaid order – please don't put any bill or price tag in the parcel.\n")
            .append("Please share the courier name and tracking number once booked. Thank you!");
        return sb.toString();
    }

    private BoutiqueOrder dropshipOrder(String orderNo) {
        BoutiqueOrder order = orderRepository.findByOrderNo(orderNo.trim().toUpperCase(Locale.ROOT))
            .orElseThrow(() -> AgentApiException.notFound("ORDER_NOT_FOUND", "No order " + orderNo));
        if (!order.isDropship()) throw AgentApiException.conflict("NOT_DROPSHIP", order.getOrderNo() + " is not a dropship order");
        return order;
    }

    private static void requirePaid(BoutiqueOrder order) {
        if (order.getStatus() == BoutiqueOrderStatus.DRAFT || order.getStatus() == BoutiqueOrderStatus.CANCELLED) {
            throw AgentApiException.conflict("ORDER_NOT_PAID", order.getOrderNo() + " is not paid yet (" + order.getStatus() + ")");
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private Supplier supplier(Object[] a) {
        BigDecimal extra = a[11] == null ? null : new BigDecimal(a[11].toString());
        return new Supplier(((Number) a[0]).intValue(), (String) a[1], (String) a[2], (String) a[3], (String) a[4],
            (String) a[5], (String) a[6], a[7] == null ? 2 : ((Number) a[7]).intValue(), (String) a[8], !Boolean.FALSE.equals(a[9]),
            parseSlabs((String) a[10], null).perZone(), extra, !Boolean.FALSE.equals(a[12]),
            a[13] instanceof java.sql.Timestamp ts ? ts.toLocalDateTime() : a[13] instanceof java.time.LocalDateTime l ? l : null);
    }

    /** A non-negative amount from the UI body, or null when empty. */
    private static BigDecimal money(Object v, String label) {
        String t = v == null ? "" : v.toString().trim();
        if (t.isEmpty()) return null;
        try {
            BigDecimal d = new BigDecimal(t);
            if (d.signum() < 0) throw bad(label + " can't be negative");
            return PricingService.money(d);
        } catch (NumberFormatException e) {
            throw bad(label + " must be a number");
        }
    }

    /** 10-digit Indian numbers get 91 in front for wa.me. */
    static String waNumber(String phone) {
        String d = digits(phone);
        if (d.isEmpty()) return null;
        return d.length() == 10 ? "91" + d : d;
    }

    static String rupees(BigDecimal v) {
        return "₹" + String.format(Locale.ENGLISH, "%,.0f", nz(v));
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String digits(String s) {
        return s == null ? "" : s.replaceAll("\\D", "");
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString().trim();
    }

    private static String nullable(Object o) {
        String s = str(o);
        return s.isEmpty() ? null : s;
    }

    private static AgentApiException bad(String message) {
        return new AgentApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message, null);
    }
}

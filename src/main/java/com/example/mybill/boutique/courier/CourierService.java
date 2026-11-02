package com.example.mybill.boutique.courier;

import com.example.mybill.boutique.entity.*;
import com.example.mybill.boutique.exception.AgentApiException;
import com.example.mybill.boutique.repository.BoutiqueCustomerRepository;
import com.example.mybill.boutique.repository.BoutiqueDesignRepository;
import com.example.mybill.boutique.repository.BoutiqueOrderRepository;
import com.example.mybill.multitenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Courier shipments for paid boutique orders (staff books from WhatsApp Orders):
 *   book   -> Delhivery shipment (AWB) + pickup request, order DISPATCHED, the customer gets the tracking link on WhatsApp
 *   label  -> the shipping label PDF to print
 *   cancel -> only before pickup; the order goes back to PAID
 *   sync   -> courier status of shipped orders; "out for delivery" and "delivered" are messaged once, DELIVERED set
 */
@Service
public class CourierService {

    private static final Logger log = LoggerFactory.getLogger(CourierService.class);
    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    public static final String DELHIVERY = "DELHIVERY";
    public static final String DISPATCHED = "order.dispatched", OUT_FOR_DELIVERY = "order.out_for_delivery",
        DELIVERED = "order.delivered";

    @Autowired private DelhiveryClient delhivery;
    @Autowired private CourierRateService rates;
    @Autowired private BoutiqueOrderRepository orderRepository;
    @Autowired private BoutiqueDesignRepository designRepository;
    @Autowired private BoutiqueCustomerRepository customerRepository;
    @Autowired private ApplicationEventPublisher events;

    /** Pickup time sent to Delhivery; bookings after the cut-off hour get tomorrow's pickup. */
    @Value("${delhivery.pickup-time:15:00:00}") private String pickupTime;
    @Value("${delhivery.pickup-cutoff-hour:13}") private int pickupCutoffHour;

    public record Plan(boolean enabled, Integer contentsGrams, String cover, List<String> covers, Integer parcelGrams,
                       Integer chargeableGrams, BigDecimal estimatedCost, String missing) {}

    /** What would be booked: weight from the items (or the override), cover, Delhivery's cost. Nothing is saved. */
    @Transactional(readOnly = true)
    public Plan plan(String orderNo, Integer weightOverride, String coverName) {
        BoutiqueOrder order = find(orderNo);
        List<String> covers = rates.covers().stream().map(CourierRateService.Cover::name).toList();
        Contents contents = weightOverride != null && weightOverride > 0 ? new Contents(weightOverride, null) : contents(order);
        if (contents.grams() == null) {
            return new Plan(delhivery.enabled(), null, null, covers, null, null, null, contents.missing());
        }
        CourierRateService.ParcelPlan p = rates.plan(contents.grams(), rates.cover(coverName).orElse(null));
        BigDecimal cost = delhivery.shippingCost(order.getDeliveryPincode(), p.chargeableGrams()).orElse(null);
        return new Plan(delhivery.enabled(), contents.grams(), p.cover().name(), covers, p.parcelGrams(),
            p.chargeableGrams(), cost, null);
    }

    @Transactional
    public BoutiqueOrder book(String orderNo, Integer weightOverride, String coverName, String staff) {
        BoutiqueOrder order = find(orderNo);
        if (order.getAwb() != null) {
            throw AgentApiException.conflict("COURIER_ALREADY_BOOKED", "Courier already booked for " + order.getOrderNo()
                + " (AWB " + order.getAwb() + ")");
        }
        if (order.getStatus() != BoutiqueOrderStatus.PAID && order.getStatus() != BoutiqueOrderStatus.IN_STITCHING) {
            throw AgentApiException.conflict("ORDER_NOT_DISPATCHABLE", "Only paid orders can be shipped (this one is "
                + order.getStatus() + ")");
        }
        Contents contents = weightOverride != null && weightOverride > 0 ? new Contents(weightOverride, null) : contents(order);
        if (contents.grams() == null) {
            throw AgentApiException.unprocessable("WEIGHT_REQUIRED", "Enter the parcel weight: " + contents.missing(), Map.of());
        }
        CourierRateService.ParcelPlan p = rates.plan(contents.grams(), rates.cover(coverName).orElse(null));
        String awb;
        try {
            awb = delhivery.createShipment(new DelhiveryClient.Shipment(order.getOrderNo(), order.getDeliveryName(),
                address(order), order.getDeliveryPincode(), order.getDeliveryCity(), order.getDeliveryState(),
                phone10(order.getDeliveryPhone()), description(order), order.getTotal(), pieces(order), p.parcel(),
                order.getCreatedAt() == null ? LocalDate.now(IST) : order.getCreatedAt().toLocalDate()));
        } catch (CourierException e) {
            throw AgentApiException.unprocessable("COURIER_ERROR", e.getMessage(), Map.of());
        }
        log.info("COURIER_BOOKED order={} awb={} parcel={}g cover={} by={}", order.getOrderNo(), awb, p.parcelGrams(),
            p.cover().name(), staff);
        LocalDateTime now = LocalDateTime.now();
        order.setCourier(DELHIVERY);
        order.setAwb(awb);
        order.setShipWeightGrams(p.parcelGrams());
        order.setCourierCost(delhivery.shippingCost(order.getDeliveryPincode(), p.chargeableGrams()).orElse(null));
        order.setCourierPickupId(delhivery.requestPickup(pickupDate(), pickupTime, 1).orElse(null));
        order.setCourierStatus("Manifested");
        order.setCourierStatusAt(now);
        order.setShippedAt(now);
        order.setCourierNotified("dispatched");
        order.setStatus(BoutiqueOrderStatus.DISPATCHED);
        order.setUpdatedAt(now);
        BoutiqueOrder saved = orderRepository.save(order);
        events.publishEvent(event(DISPATCHED, saved, null));
        return saved;
    }

    @Transactional(readOnly = true)
    public String label(String orderNo) {
        BoutiqueOrder order = find(orderNo);
        if (order.getAwb() == null) throw AgentApiException.conflict("COURIER_NOT_BOOKED", "No courier booked for " + orderNo);
        try {
            return delhivery.labelUrl(order.getAwb()).orElseThrow(() ->
                AgentApiException.unprocessable("COURIER_ERROR", "Delhivery has no label for AWB " + order.getAwb() + " yet", Map.of()));
        } catch (CourierException e) {
            throw AgentApiException.unprocessable("COURIER_ERROR", e.getMessage(), Map.of());
        }
    }

    /** Before pickup only: cancels the Delhivery shipment and puts the order back to PAID. */
    @Transactional
    public BoutiqueOrder cancel(String orderNo, String staff) {
        BoutiqueOrder order = find(orderNo);
        if (order.getAwb() == null) throw AgentApiException.conflict("COURIER_NOT_BOOKED", "No courier booked for " + orderNo);
        if (order.getStatus() == BoutiqueOrderStatus.DELIVERED) {
            throw AgentApiException.conflict("COURIER_DELIVERED", "Already delivered, can't cancel");
        }
        try {
            delhivery.cancel(order.getAwb());
        } catch (CourierException e) {
            throw AgentApiException.unprocessable("COURIER_ERROR", e.getMessage(), Map.of());
        }
        log.info("COURIER_CANCELLED order={} awb={} by={}", order.getOrderNo(), order.getAwb(), staff);
        order.setAwb(null);
        order.setCourier(null);
        order.setCourierPickupId(null);
        order.setCourierStatus(null);
        order.setCourierLocation(null);
        order.setCourierStatusAt(null);
        order.setCourierNotified(null);
        order.setShippedAt(null);
        order.setStatus(order.getStitchingOrderId() != null && !order.isGoods() ? BoutiqueOrderStatus.IN_STITCHING
            : BoutiqueOrderStatus.PAID);
        order.setUpdatedAt(LocalDateTime.now());
        return orderRepository.save(order);
    }

    /** Live courier status for one order (the agent's "where is my order"); empty if not shipped. */
    @Transactional(readOnly = true)
    public Optional<Map<String, Object>> tracking(String orderNo) {
        BoutiqueOrder order = find(orderNo);
        if (order.getAwb() == null) return Optional.empty();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("courier", order.getCourier());
        out.put("awb", order.getAwb());
        out.put("tracking_url", DelhiveryClient.trackingUrl(order.getAwb()));
        out.put("shipped_at", order.getShippedAt());
        delhivery.track(order.getAwb()).ifPresentOrElse(t -> {
            out.put("status", t.status());
            out.put("location", t.location());
            out.put("details", t.instructions());
            out.put("status_at", t.statusDateTime());
            out.put("expected_delivery", t.expectedDelivery());
            out.put("delivered", t.delivered());
        }, () -> {
            out.put("status", order.getCourierStatus());
            out.put("location", order.getCourierLocation());
            out.put("status_at", order.getCourierStatusAt());
        });
        return Optional.of(out);
    }

    /** One pass over this firm's shipped orders: store the latest status, message updates once, mark delivered. */
    @Transactional
    public Map<String, Integer> sync() {
        Map<String, Integer> stats = new LinkedHashMap<>(Map.of("checked", 0, "updated", 0, "out_for_delivery", 0, "delivered", 0));
        if (!delhivery.enabled()) return stats;
        for (BoutiqueOrder order : orderRepository.findByStatusAndAwbIsNotNullOrderByShippedAtAsc(BoutiqueOrderStatus.DISPATCHED)) {
            Optional<DelhiveryClient.Tracking> t = delhivery.track(order.getAwb());
            stats.merge("checked", 1, Integer::sum);
            if (t.isEmpty()) continue;
            DelhiveryClient.Tracking tr = t.get();
            if (!Objects.equals(tr.status(), order.getCourierStatus()) || !Objects.equals(tr.location(), order.getCourierLocation())) {
                order.setCourierStatus(trim(tr.status(), 60));
                order.setCourierLocation(trim(tr.location(), 150));
                order.setCourierStatusAt(LocalDateTime.now());
                stats.merge("updated", 1, Integer::sum);
            }
            if (tr.delivered()) {
                order.setStatus(BoutiqueOrderStatus.DELIVERED);
                order.setDeliveredAt(LocalDateTime.now());
                if (!"delivered".equals(order.getCourierNotified())) {
                    order.setCourierNotified("delivered");
                    events.publishEvent(event(DELIVERED, order, null));
                }
                stats.merge("delivered", 1, Integer::sum);
                log.info("COURIER_DELIVERED order={} awb={}", order.getOrderNo(), order.getAwb());
            } else if (tr.outForDelivery() && !"out_for_delivery".equals(order.getCourierNotified())) {
                order.setCourierNotified("out_for_delivery");
                events.publishEvent(event(OUT_FOR_DELIVERY, order, tr.location()));
                stats.merge("out_for_delivery", 1, Integer::sum);
            }
            order.setUpdatedAt(LocalDateTime.now());
            orderRepository.save(order);
        }
        return stats;
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private record Contents(Integer grams, String missing) {}

    /** Contents weight from the order lines (default weight for a product / design without one). */
    private Contents contents(BoutiqueOrder order) {
        Set<Integer> productIds = order.getItems().stream().map(BoutiqueOrderItem::getFabricProductId)
            .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Integer, Integer> weights = rates.productWeights(productIds);
        BigDecimal total = BigDecimal.ZERO;
        for (BoutiqueOrderItem i : order.getItems()) {
            if (i.getFabricProductId() != null) {
                int w = rates.weightOrDefault(weights.get(i.getFabricProductId()));
                BigDecimal qty = i.getFabricMetres() != null ? i.getFabricMetres() : BigDecimal.valueOf(i.getQuantity());
                total = total.add(BigDecimal.valueOf(w).multiply(qty));
            } else {
                Integer w = i.getDesignId() == null ? null
                    : designRepository.findById(i.getDesignId()).map(BoutiqueDesign::getShipWeightGrams).orElse(null);
                total = total.add(BigDecimal.valueOf((long) rates.weightOrDefault(w) * i.getQuantity()));
            }
        }
        return new Contents(total.setScale(0, java.math.RoundingMode.CEILING).intValue(), null);
    }

    private LocalDate pickupDate() {
        ZonedDateTime now = ZonedDateTime.now(IST);
        LocalDate d = now.getHour() < pickupCutoffHour ? now.toLocalDate() : now.toLocalDate().plusDays(1);
        return d.getDayOfWeek() == DayOfWeek.SUNDAY ? d.plusDays(1) : d;
    }

    private BoutiqueOrder find(String orderNo) {
        return orderRepository.findByOrderNo(orderNo.trim().toUpperCase(Locale.ROOT))
            .orElseThrow(() -> AgentApiException.notFound("ORDER_NOT_FOUND", "No order " + orderNo));
    }

    private static String address(BoutiqueOrder o) {
        return o.getDeliveryAddressLine2() == null || o.getDeliveryAddressLine2().isBlank() ? o.getDeliveryAddressLine1()
            : o.getDeliveryAddressLine1() + ", " + o.getDeliveryAddressLine2();
    }

    private static String phone10(String phone) {
        String digits = phone == null ? "" : phone.replaceAll("\\D", "");
        return digits.length() > 10 ? digits.substring(digits.length() - 10) : digits;
    }

    private static String description(BoutiqueOrder o) {
        String d = o.getItems().stream().map(i -> i.getDesignCode() + " " + i.getDesignName()).collect(Collectors.joining(", "));
        return trim(d, 100);
    }

    private static int pieces(BoutiqueOrder o) {
        return Math.max(1, o.getItems().stream().mapToInt(i -> i.getFabricMetres() != null ? 1 : i.getQuantity()).sum());
    }

    private static String trim(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max) : s;
    }

    private CourierEvent event(String type, BoutiqueOrder order, String location) {
        BoutiqueCustomer c = customerRepository.findById(order.getCustomerId()).orElse(null);
        String phone = c != null && c.getPhoneE164() != null ? c.getPhoneE164() : order.getDeliveryPhone();
        return new CourierEvent(type, order.getOrderNo(), phone, c != null ? c.getCustomerName() : order.getDeliveryName(),
            order.getOrderType(), order.getCourier(), order.getAwb(), DelhiveryClient.trackingUrl(order.getAwb()), location,
            type + ":" + order.getOrderNo() + ":" + order.getAwb(), String.valueOf(TenantContext.getCurrentTenant()));
    }
}

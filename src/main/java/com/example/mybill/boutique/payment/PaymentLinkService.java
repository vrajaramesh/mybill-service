package com.example.mybill.boutique.payment;

import com.example.mybill.boutique.dto.PaymentInfo;
import com.example.mybill.boutique.entity.*;
import com.example.mybill.boutique.service.AgentCatalogService;
import com.example.mybill.boutique.service.AgentOrderService;
import com.example.mybill.boutique.exception.AgentApiException;
import com.example.mybill.boutique.repository.*;
import com.example.mybill.multitenancy.TenantContext;
import com.example.mybill.service.CustomerRepository;
import com.example.mybill.service.BoutiqueService;
import com.example.mybill.service.StitchingOrder;
import com.example.mybill.service.StitchingOrderItem;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Razorpay Payment Links for boutique orders. mybill owns all payment state; every handler is idempotent and
 * moves orders forward only (BoutiqueOrderStatus.canMoveTo), because webhooks may arrive late, twice or out of order.
 */
@Service
public class PaymentLinkService {

    private static final Logger log = LoggerFactory.getLogger(PaymentLinkService.class);
    /** Same clock as the rest of the boutique tables (server default zone; UTC on Cloud Run). */
    static final ZoneId ZONE = ZoneId.systemDefault();
    static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Autowired private BoutiqueOrderRepository orderRepository;
    @Autowired private BoutiqueOrderPaymentRepository paymentRepository;
    @Autowired private BoutiqueCustomerRepository customerRepository;
    @Autowired private BoutiqueDesignRepository designRepository;
    @Autowired private CustomerRepository retailCustomerRepository;
    @Autowired private BoutiqueService boutiqueService;
    @Autowired private RazorpayPaymentLinkClient razorpay;
    @Autowired private ApplicationEventPublisher events;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private com.example.mybill.boutique.service.GoodsService goodsService;

    @Value("${boutique.payment.link-expiry-hours:24}") private int expiryHours;
    @Value("${boutique.payment.callback-url:}") private String callbackUrl;
    @Value("${boutique.payment.brand:SRISA Boutique}") private String brand;

    public enum Outcome { PAID, ALREADY_PAID, NEEDS_REVIEW, CLOSED, IGNORED, UNKNOWN_LINK }

    // ── Create / read ────────────────────────────────────────────────────────

    /** Active link if there is one, otherwise a new Razorpay link for the order total (from the DB). */
    @Transactional
    public PaymentInfo createOrGetLink(String orderNo) {
        BoutiqueOrder order = requireOrder(orderNo);
        if (!order.getStatus().isPayable()) {
            throw AgentApiException.conflict("ORDER_NOT_PAYABLE", "Order " + order.getOrderNo() + " is " + order.getStatus());
        }
        LocalDateTime now = now();
        Optional<BoutiqueOrderPayment> latest = paymentRepository.findFirstByOrderIdOrderByCreatedAtDescPaymentIdDesc(order.getOrderId());
        if (latest.isPresent() && latest.get().isActive(now)) {
            return toInfo(order, latest.get());
        }

        long amountPaise = order.getTotal().setScale(2, java.math.RoundingMode.HALF_UP).movePointRight(2).longValueExact();
        long n = paymentRepository.countByOrderId(order.getOrderId());
        String reference = n == 0 ? order.getOrderNo() : order.getOrderNo() + "-" + (n + 1);
        if (reference.length() > 40) reference = reference.substring(reference.length() - 40);
        Instant expireBy = Instant.now().plus(Duration.ofHours(Math.max(1, expiryHours)));  // Razorpay: >= 15 min ahead

        BoutiqueCustomer customer = customerRepository.findById(order.getCustomerId()).orElse(null);
        Map<String, Object> customerJson = new LinkedHashMap<>();
        customerJson.put("name", customer != null && customer.getCustomerName() != null ? customer.getCustomerName() : order.getDeliveryName());
        String phone = customer != null && customer.getPhoneE164() != null ? customer.getPhoneE164() : order.getDeliveryPhone();
        customerJson.put("contact", phone == null ? null : phone.replaceAll("[^0-9]", ""));  // digits, no '+'

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", amountPaise);
        body.put("currency", "INR");
        body.put("accept_partial", false);
        body.put("reference_id", reference);
        body.put("description", brand + " order " + order.getOrderNo());
        body.put("customer", customerJson);
        body.put("notify", Map.of("sms", false, "email", false));
        body.put("reminder_enable", false);
        body.put("expire_by", expireBy.getEpochSecond());
        if (!callbackUrl.isBlank()) {
            body.put("callback_url", callbackUrl);
            body.put("callback_method", "get");
        }
        body.put("notes", Map.of("order_no", order.getOrderNo(), "customer_id", String.valueOf(order.getCustomerId()),
            "tenant", String.valueOf(TenantContext.getCurrentTenant())));

        JsonNode link;
        try {
            link = razorpay.createPaymentLink(body);
        } catch (RazorpayPaymentLinkClient.RazorpayRejected e) {
            log.error("Razorpay rejected payment link for {}: {}", order.getOrderNo(), e.getMessage());
            throw new AgentApiException(HttpStatus.BAD_GATEWAY, "PAYMENT_PROVIDER_REJECTED",
                "The payment provider rejected the request", Map.of());
        } catch (RazorpayPaymentLinkClient.RazorpayUnavailable e) {
            log.error("Razorpay unavailable for {}: {}", order.getOrderNo(), e.getMessage());
            throw new AgentApiException(HttpStatus.SERVICE_UNAVAILABLE, "PAYMENT_PROVIDER_UNAVAILABLE",
                "The payment provider is not reachable, try again shortly", Map.of());
        }

        BoutiqueOrderPayment payment = new BoutiqueOrderPayment();
        payment.setOrderId(order.getOrderId());
        payment.setRazorpayPaymentLinkId(link.path("id").asText());
        payment.setReferenceId(reference);
        payment.setShortUrl(link.path("short_url").asText(null));
        payment.setAmountPaise(amountPaise);
        payment.setCurrency("INR");
        payment.setStatus(BoutiqueOrderPayment.CREATED);
        long expiry = link.path("expire_by").asLong(expireBy.getEpochSecond());
        payment.setExpiresAt(LocalDateTime.ofInstant(Instant.ofEpochSecond(expiry), ZONE));
        payment.setCreatedAt(now);
        payment.setUpdatedAt(now);
        paymentRepository.save(payment);

        moveTo(order, BoutiqueOrderStatus.PAYMENT_PENDING);
        log.info("Payment link {} created for {} ({} paise, ref {})", payment.getRazorpayPaymentLinkId(),
            order.getOrderNo(), amountPaise, reference);
        return toInfo(order, payment);
    }

    @Transactional(readOnly = true)
    public PaymentInfo latestPayment(String orderNo) {
        BoutiqueOrder order = requireOrder(orderNo);
        return paymentRepository.findFirstByOrderIdOrderByCreatedAtDescPaymentIdDesc(order.getOrderId())
            .map(p -> toInfo(order, p))
            .orElseThrow(() -> AgentApiException.notFound("PAYMENT_NOT_FOUND", "No payment link for order " + orderNo));
    }

    /** Latest payment per order id (for order lists). */
    @Transactional(readOnly = true)
    public Map<Integer, BoutiqueOrderPayment> latestByOrder(Collection<Integer> orderIds) {
        if (orderIds.isEmpty()) return Map.of();
        return paymentRepository.findByOrderIdInOrderByCreatedAtDescPaymentIdDesc(orderIds).stream()
            .collect(Collectors.toMap(BoutiqueOrderPayment::getOrderId, p -> p, (first, older) -> first));
    }

    public PaymentInfo toInfo(BoutiqueOrder order, BoutiqueOrderPayment p) {
        return new PaymentInfo(order.getOrderNo(), order.getStatus().name(), p.getStatus(), p.getShortUrl(),
            BigDecimal.valueOf(p.getAmountPaise()).movePointLeft(2), p.getCurrency(), p.getExpiresAt(), p.getPaidAt(),
            p.getRazorpayPaymentId(), Boolean.TRUE.equals(p.getNeedsReview()));
    }

    // ── Webhook / reconciliation handlers (idempotent, forward-only) ─────────

    /** payment_link.paid (or a paid link found by reconciliation). */
    @Transactional
    public Outcome handlePaid(String linkId, long amountPaidPaise, String currency, String razorpayPaymentId,
                              Instant paidAt, String eventId) {
        BoutiqueOrderPayment payment = paymentRepository.findByRazorpayPaymentLinkId(linkId).orElse(null);
        if (payment == null) {
            log.warn("Paid event for unknown payment link {} (event {})", linkId, eventId);
            return Outcome.UNKNOWN_LINK;
        }
        if (BoutiqueOrderPayment.PAID.equals(payment.getStatus())) return Outcome.ALREADY_PAID;

        if (amountPaidPaise != payment.getAmountPaise() || !"INR".equalsIgnoreCase(currency)) {
            payment.setNeedsReview(true);
            payment.setReviewReason("Paid " + amountPaidPaise + " " + currency + " but expected "
                + payment.getAmountPaise() + " INR (payment " + razorpayPaymentId + ", event " + eventId + ")");
            payment.setUpdatedAt(now());
            log.error("PAYMENT_AMOUNT_MISMATCH link={} expected={} paid={} {} - not marked paid, needs manual review",
                linkId, payment.getAmountPaise(), amountPaidPaise, currency);
            return Outcome.NEEDS_REVIEW;
        }

        LocalDateTime paid = paidAt == null ? now() : LocalDateTime.ofInstant(paidAt, ZONE);
        payment.setStatus(BoutiqueOrderPayment.PAID);   // a late 'expired' can never undo this (see handleClosed)
        payment.setRazorpayPaymentId(razorpayPaymentId);
        payment.setPaidAt(paid);
        payment.setUpdatedAt(now());

        BoutiqueOrder order = orderRepository.findById(payment.getOrderId()).orElseThrow();
        if (moveTo(order, BoutiqueOrderStatus.PAID)) {
            order.setPaidAt(paid);
        }
        boolean fabricOnly = order.isGoods();   // fabric / readymade: nothing to stitch, dispatched from stock
        if (fabricOnly && order.getStockReducedAt() == null) {
            goodsService.reduceStock(order);
            order.setStockReducedAt(now());
            log.info("STOCK_REDUCED order={}", order.getOrderNo());
        }
        if (!fabricOnly && order.getStitchingOrderId() == null && !order.getItems().isEmpty()) {
            order.setStitchingOrderId(createStitchingOrder(order, payment).getOrderId());
        }
        if (!fabricOnly && order.getMeasurementStatus() == null && needsExactMeasurements(order)) {
            order.setMeasurementStatus(AgentOrderService.MEASUREMENT_PENDING);   // the tailor calls her
            log.info("MEASUREMENT_CALL_NEEDED order={}", order.getOrderNo());
        }
        log.info("ORDER_PAID order={} payment={} link={}", order.getOrderNo(), razorpayPaymentId, linkId);
        events.publishEvent(agentEvent(AgentEvent.ORDER_PAID, order, eventId, null));
        return Outcome.PAID;
    }

    /** payment_link.expired / payment_link.cancelled. The order stays PAYMENT_PENDING (a new link can be made). */
    @Transactional
    public Outcome handleClosed(String linkId, String newStatus, String eventId) {
        BoutiqueOrderPayment payment = paymentRepository.findByRazorpayPaymentLinkId(linkId).orElse(null);
        if (payment == null) {
            log.warn("{} event for unknown payment link {} (event {})", newStatus, linkId, eventId);
            return Outcome.UNKNOWN_LINK;
        }
        if (!BoutiqueOrderPayment.CREATED.equals(payment.getStatus())) {
            return Outcome.IGNORED;   // already paid (out-of-order event) or already closed
        }
        payment.setStatus(newStatus);
        payment.setUpdatedAt(now());
        BoutiqueOrder order = orderRepository.findById(payment.getOrderId()).orElseThrow();
        String event = BoutiqueOrderPayment.EXPIRED.equals(newStatus) ? AgentEvent.LINK_EXPIRED : AgentEvent.LINK_CANCELLED;
        events.publishEvent(agentEvent(event, order, eventId, payment.getShortUrl()));
        return Outcome.CLOSED;
    }

    /** Links still 'created' in our DB, created more than 5 minutes ago and not yet expired. */
    @Transactional(readOnly = true)
    public List<String> openLinkIdsForReconciliation() {
        LocalDateTime now = now();
        return paymentRepository.findByStatusAndCreatedAtBeforeAndExpiresAtAfter(
                BoutiqueOrderPayment.CREATED, now.minusMinutes(5), now).stream()
            .map(BoutiqueOrderPayment::getRazorpayPaymentLinkId).filter(Objects::nonNull).toList();
    }

    // ── internals ────────────────────────────────────────────────────────────

    private BoutiqueOrder requireOrder(String orderNo) {
        return orderRepository.findByOrderNo(orderNo.trim().toUpperCase(Locale.ROOT))
            .orElseThrow(() -> AgentApiException.notFound("ORDER_NOT_FOUND", "No order " + orderNo));
    }

    /** Forward-only status change. Returns true if the status changed. */
    private boolean moveTo(BoutiqueOrder order, BoutiqueOrderStatus next) {
        if (!order.getStatus().canMoveTo(next)) {
            if (order.getStatus() != next) {
                log.info("Order {} stays {} (ignored move to {})", order.getOrderNo(), order.getStatus(), next);
            }
            return false;
        }
        order.setStatus(next);
        order.setUpdatedAt(now());
        return true;
    }

    /** Hand the paid order to the tailors: a stitching order in the existing Boutique workshop queue. */
    private StitchingOrder createStitchingOrder(BoutiqueOrder order, BoutiqueOrderPayment payment) {
        BoutiqueOrderItem line = order.getItems().get(0);
        BoutiqueDesign design = line.getDesignId() == null ? null : designRepository.findById(line.getDesignId()).orElse(null);
        Map<String, Object> m = readMap(line.getMeasurementsSnapshot());
        List<Map<String, Object>> options = readList(line.getOptionsSnapshot());

        StitchingOrderItem item = new StitchingOrderItem();
        item.setGarmentType(design != null ? design.getGarmentType() : line.getDesignName());
        item.setFabricDescription(truncate((design != null && design.getFabricDescription() != null
            ? design.getFabricDescription() + " · " : "") + line.getDesignCode() + " " + line.getDesignName(), 200));
        item.setQuantity(line.getQuantity());
        item.setStitchingCharges(line.getUnitPrice());
        item.setChest(decimal(m.get("bust_in")));
        item.setWaist(decimal(m.get("waist_in")));
        item.setHip(decimal(m.get("hip_in")));
        StringBuilder notes = new StringBuilder("Size ").append(line.getSizeCode());
        if (m.get("height_cm") != null) notes.append(", height ").append(m.get("height_cm")).append(" cm");
        if (m.get("fit_preference") != null) notes.append(", ").append(String.valueOf(m.get("fit_preference")).toLowerCase()).append(" fit");
        if (!options.isEmpty()) notes.append(". Options: ").append(options.stream().map(o -> String.valueOf(o.get("name"))).collect(Collectors.joining(", ")));
        if (m.get("notes") != null) notes.append(". Notes: ").append(m.get("notes"));
        item.setSpecialInstructions(truncate(notes.toString(), 500));

        StitchingOrder so = new StitchingOrder();
        so.setCustomer(retailCustomerRepository.findById(order.getCustomerId()).orElse(null));
        so.setStatus("RECEIVED");
        so.setPriority("NORMAL");
        so.setOrderDate(LocalDate.now(IST));
        so.setDeliveryDate(order.getEstimatedDeliveryDate());
        so.setNotes(truncate("Online order " + order.getOrderNo() + " (" + order.getChannel() + "), paid via Razorpay "
            + payment.getRazorpayPaymentId() + ". Ship to: " + order.getDeliveryName() + ", " + order.getDeliveryAddressLine1()
            + ", " + order.getDeliveryCity() + " " + order.getDeliveryPincode() + ", " + order.getDeliveryPhone(), 2000));
        so.setAdvancePaid(line.getLineTotal());  // garment fully paid online (delivery charge excluded)
        so.getItems().add(item);
        StitchingOrder saved = boutiqueService.createOrder(so);
        saved.getPayments().forEach(p -> { p.setPaymentMethod("RAZORPAY"); p.setNotes("Online order " + order.getOrderNo()); });
        log.info("Stitching order {} created for paid order {}", saved.getOrderNumber(), order.getOrderNo());
        return saved;
    }

    private AgentEvent agentEvent(String event, BoutiqueOrder order, String eventId, String shortUrl) {
        BoutiqueCustomer c = customerRepository.findById(order.getCustomerId()).orElse(null);
        String phone = c != null && c.getPhoneE164() != null ? c.getPhoneE164() : order.getDeliveryPhone();
        BoutiqueOrderItem item = order.getItems().isEmpty() ? null : order.getItems().get(0);
        return new AgentEvent(event, order.getOrderNo(), phone, c != null ? c.getCustomerName() : order.getDeliveryName(),
            order.getTotal(), order.getEstimatedDeliveryDate(), eventId, shortUrl,
            AgentOrderService.MEASUREMENT_PENDING.equals(order.getMeasurementStatus()),
            item == null ? null : item.getDesignCode(),
            item == null ? null : item.getDesignName()
                + (AgentCatalogService.CUSTOM_SIZE.equals(item.getSizeCode()) ? ", custom size" : ""),
            order.getOrderType(), order.isGoods() ? itemsSummary(order) : null,
            String.join(", ", java.util.stream.Stream.of(order.getDeliveryName(), order.getDeliveryAddressLine1(),
                order.getDeliveryAddressLine2(), order.getDeliveryCity(), order.getDeliveryState() + " " + order.getDeliveryPincode())
                .filter(v -> v != null && !v.isBlank()).toList()));
    }

    /** "MCS-1B Blue check – 2.5 m; KUR-3A – size M × 2" for the staff dispatch alert. */
    private static String itemsSummary(BoutiqueOrder order) {
        return order.getItems().stream().map(i -> i.getDesignCode() + " " + i.getDesignName() + " – "
                + (i.getFabricMetres() != null ? i.getFabricMetres().stripTrailingZeros().toPlainString() + " m"
                   : ("-".equals(i.getSizeCode()) ? "" : "size " + i.getSizeCode() + " ") + "× " + i.getQuantity()))
            .collect(java.util.stream.Collectors.joining("; "));
    }

    /** A custom size, or any item's design flagged "exact measurements needed" in the catalog. */
    private boolean needsExactMeasurements(BoutiqueOrder order) {
        if (order.getItems().stream().anyMatch(i -> AgentCatalogService.CUSTOM_SIZE.equals(i.getSizeCode()))) return true;
        return order.getItems().stream()
            .filter(i -> i.getDesignId() != null)
            .map(i -> designRepository.findById(i.getDesignId()).orElse(null))
            .anyMatch(d -> d != null && d.needsExactMeasurements());
    }

    private Map<String, Object> readMap(String json) {
        try { return json == null ? Map.of() : objectMapper.readValue(json, new TypeReference<>() {}); }
        catch (Exception e) { return Map.of(); }
    }

    private List<Map<String, Object>> readList(String json) {
        try { return json == null ? List.of() : objectMapper.readValue(json, new TypeReference<>() {}); }
        catch (Exception e) { return List.of(); }
    }

    private static BigDecimal decimal(Object v) {
        try { return v == null ? null : new BigDecimal(String.valueOf(v)); } catch (NumberFormatException e) { return null; }
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZONE);
    }
}

package com.example.mybill.boutique.service;

import com.example.mybill.boutique.dto.*;
import com.example.mybill.boutique.entity.*;
import com.example.mybill.boutique.exception.AgentApiException;
import com.example.mybill.boutique.repository.BoutiqueDesignRepository;
import com.example.mybill.boutique.repository.BoutiqueOrderRepository;
import com.example.mybill.boutique.repository.CustomisationOptionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.mybill.boutique.repository.BoutiqueCustomerRepository;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Quotes and DRAFT orders for the agent. All prices come from PricingService. */
@Service
public class AgentOrderService {

    @Autowired private BoutiqueDesignRepository designRepository;
    @Autowired private CustomisationOptionRepository optionRepository;
    @Autowired private BoutiqueOrderRepository orderRepository;
    @Autowired private AgentCatalogService catalogService;
    @Autowired private AgentCustomerService customerService;
    @Autowired private PricingService pricingService;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ShippingService shippingService;
    @Autowired private BoutiqueCustomerRepository customerRepository;
    @Autowired private FabricCatalogService fabricCatalog;
    /** Days to cut and pack a fabric order before it ships. */
    @org.springframework.beans.factory.annotation.Value("${boutique.fabric.dispatch-days:1}")
    private int fabricDispatchDays;
    @Autowired @org.springframework.context.annotation.Lazy
    private com.example.mybill.boutique.payment.PaymentLinkService paymentLinkService;

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    /** size_code stored on fabric order items (no garment size). */
    public static final String FABRIC_SIZE = "FABRIC";
    public static final String MEASUREMENT_PENDING = "PENDING_CALL";
    public static final String MEASUREMENT_TAKEN = "TAKEN";
    static final int MAX_LIST = 100;

    public record CreateResult(OrderResponse order, boolean created) {}

    private record Priced(BoutiqueDesign design, String sizeCode, PriceBreakdown breakdown) {}

    /** Price breakdown for a design + size + options; nothing is saved. */
    @Transactional(readOnly = true)
    public QuoteResponse quote(QuoteRequest request) {
        Priced p = price(request.designCode(), request.sizeCode(), request.optionIds(),
            request.qty() == null ? 1 : request.qty());
        ShippingService.ShippingEstimate ship = shippingService.estimate(request.pincode(),
            leadTime(p.design()), LocalDate.now(IST));
        return new QuoteResponse(p.design().getCode(), p.design().displayName(), p.sizeCode(),
            p.design().getLeadTimeDays(), p.breakdown(), ship.serviceable(), ship.transitDays(),
            ship.estimatedDeliveryDate());
    }

    /**
     * Creates a DRAFT order. A repeated idempotency_key returns the existing order (created = false).
     * A concurrent duplicate that slips past the lookup hits uq_boutique_orders_idempotency_key; the controller
     * then re-reads it with findByIdempotencyKey.
     */
    @Transactional
    public CreateResult create(CreateOrderRequest request) {
        Optional<BoutiqueOrder> existing = orderRepository.findByIdempotencyKey(request.idempotencyKey());
        if (existing.isPresent()) return new CreateResult(toResponse(sameCustomer(existing.get(), request)), false);

        if (!customerService.exists(request.customerId())) {
            throw AgentApiException.unprocessable("CUSTOMER_NOT_FOUND", "No customer with id " + request.customerId(),
                Map.of("customer_id", request.customerId()));
        }
        Priced p = price(request.designCode(), request.sizeCode(), request.optionIds(), request.qty());
        PriceBreakdown b = p.breakdown();
        LocalDateTime now = LocalDateTime.now();
        ShippingService.ShippingEstimate ship = shippingService.estimate(request.delivery().pincode(),
            leadTime(p.design()), LocalDate.now(IST));
        if (!ship.serviceable()) {
            throw AgentApiException.unprocessable("PINCODE_NOT_SERVICEABLE", "We cannot deliver to this pincode yet",
                Map.of("pincode", request.delivery().pincode(), "reason", String.valueOf(ship.reason())));
        }

        BoutiqueOrder order = new BoutiqueOrder();
        order.setOrderNo("SB-" + orderRepository.nextOrderNumber());
        order.setCustomerId(request.customerId());
        order.setStatus(BoutiqueOrderStatus.DRAFT);
        DeliveryDetails d = request.delivery();
        order.setDeliveryName(d.name().trim());
        order.setDeliveryPhone(d.phone().trim());
        order.setDeliveryAddressLine1(d.addressLine1().trim());
        order.setDeliveryAddressLine2(d.addressLine2() == null || d.addressLine2().isBlank() ? null : d.addressLine2().trim());
        order.setDeliveryCity(d.city().trim());
        order.setDeliveryState(d.state().trim());
        order.setDeliveryPincode(d.pincode());
        order.setSubtotal(b.subtotal());
        order.setDeliveryCharge(b.deliveryCharge());
        order.setTotal(b.total());
        order.setChannel(request.channel().toUpperCase(Locale.ROOT));
        order.setIdempotencyKey(request.idempotencyKey());
        order.setEstimatedDeliveryDate(ship.estimatedDeliveryDate());
        if (request.sourceType() != null && !request.sourceType().isBlank()) {
            order.setSourceType(request.sourceType());
            order.setSourceId(request.sourceId());
        }
        order.setCtwaClid(request.ctwaClid() == null || request.ctwaClid().isBlank() ? null : request.ctwaClid().trim());
        order.setCreatedAt(now);
        order.setUpdatedAt(now);

        BoutiqueOrderItem item = new BoutiqueOrderItem();
        item.setDesignId(p.design().getDesignId());
        item.setDesignCode(p.design().getCode());
        item.setDesignName(p.design().displayName());
        item.setSizeCode(p.sizeCode());
        item.setOptionsSnapshot(toJson(b.options()));
        item.setMeasurementsSnapshot(request.measurements() != null
            ? toJson(measurementSnapshot(request.measurements()))
            : customerService.findLatestMeasurement(request.customerId()).map(m -> toJson(measurementSnapshot(m))).orElse(null));
        item.setUnitPrice(b.unitPrice());
        item.setQuantity(b.qty());
        item.setLineTotal(b.subtotal());
        order.addItem(item);

        return new CreateResult(toResponse(orderRepository.save(order)), true);
    }

    /** Price for metres of a fabric, with delivery to the pincode if given; nothing is saved. */
    @Transactional(readOnly = true)
    public FabricQuoteResponse fabricQuote(FabricQuoteRequest request) {
        FabricResponse fabric = fabricCatalog.require(request.productId());
        PriceBreakdown b = pricingService.priceFabric(fabric, request.metres());
        ShippingService.ShippingEstimate ship = shippingService.estimate(request.pincode(), fabricDispatchDays,
            LocalDate.now(IST));
        return new FabricQuoteResponse(fabric.productId(), fabric.code(), fabric.name(), request.metres(),
            fabric.pricePerMetre(), b, ship.serviceable(), ship.transitDays(), ship.estimatedDeliveryDate());
    }

    /** A DRAFT fabric-only order (order_type FABRIC); same idempotency rules as {@link #create}. */
    @Transactional
    public CreateResult createFabric(CreateFabricOrderRequest request) {
        Optional<BoutiqueOrder> existing = orderRepository.findByIdempotencyKey(request.idempotencyKey());
        if (existing.isPresent()) return new CreateResult(toResponse(sameCustomer(existing.get(), request.customerId())), false);

        if (!customerService.exists(request.customerId())) {
            throw AgentApiException.unprocessable("CUSTOMER_NOT_FOUND", "No customer with id " + request.customerId(),
                Map.of("customer_id", request.customerId()));
        }
        FabricResponse fabric = fabricCatalog.require(request.productId());
        PriceBreakdown b = pricingService.priceFabric(fabric, request.metres());
        ShippingService.ShippingEstimate ship = shippingService.estimate(request.delivery().pincode(),
            fabricDispatchDays, LocalDate.now(IST));
        if (!ship.serviceable()) {
            throw AgentApiException.unprocessable("PINCODE_NOT_SERVICEABLE", "We cannot deliver to this pincode yet",
                Map.of("pincode", request.delivery().pincode(), "reason", String.valueOf(ship.reason())));
        }

        BoutiqueOrder order = newOrder(request.customerId(), request.delivery(), b, request.channel(),
            request.idempotencyKey(), ship, request.sourceType(), request.sourceId(), request.ctwaClid());
        order.setOrderType(BoutiqueOrder.TYPE_FABRIC);

        BoutiqueOrderItem item = new BoutiqueOrderItem();
        item.setDesignCode(fabric.code());
        item.setDesignName(fabric.name());
        item.setSizeCode(FABRIC_SIZE);
        item.setOptionsSnapshot("[]");
        item.setUnitPrice(b.subtotal());
        item.setQuantity(1);
        item.setLineTotal(b.subtotal());
        item.setFabricProductId(fabric.productId());
        item.setFabricMetres(request.metres().setScale(2, java.math.RoundingMode.UNNECESSARY));
        item.setFabricUnit("metre");
        item.setFabricPricePerUnit(fabric.pricePerMetre());
        order.addItem(item);

        return new CreateResult(toResponse(orderRepository.save(order)), true);
    }

    private BoutiqueOrder newOrder(Integer customerId, DeliveryDetails d, PriceBreakdown b, String channel,
                                   String idempotencyKey, ShippingService.ShippingEstimate ship,
                                   String sourceType, String sourceId, String ctwaClid) {
        LocalDateTime now = LocalDateTime.now();
        BoutiqueOrder order = new BoutiqueOrder();
        order.setOrderNo("SB-" + orderRepository.nextOrderNumber());
        order.setCustomerId(customerId);
        order.setStatus(BoutiqueOrderStatus.DRAFT);
        order.setDeliveryName(d.name().trim());
        order.setDeliveryPhone(d.phone().trim());
        order.setDeliveryAddressLine1(d.addressLine1().trim());
        order.setDeliveryAddressLine2(d.addressLine2() == null || d.addressLine2().isBlank() ? null : d.addressLine2().trim());
        order.setDeliveryCity(d.city().trim());
        order.setDeliveryState(d.state().trim());
        order.setDeliveryPincode(d.pincode());
        order.setSubtotal(b.subtotal());
        order.setDeliveryCharge(b.deliveryCharge());
        order.setTotal(b.total());
        order.setChannel(channel.toUpperCase(Locale.ROOT));
        order.setIdempotencyKey(idempotencyKey);
        order.setEstimatedDeliveryDate(ship.estimatedDeliveryDate());
        if (sourceType != null && !sourceType.isBlank()) {
            order.setSourceType(sourceType);
            order.setSourceId(sourceId);
        }
        order.setCtwaClid(ctwaClid == null || ctwaClid.isBlank() ? null : ctwaClid.trim());
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        return order;
    }

    @Transactional(readOnly = true)
    public Optional<OrderResponse> findByIdempotencyKey(String key, Integer customerId) {
        return orderRepository.findByIdempotencyKey(key)
            .map(o -> toResponse(sameCustomer(o, customerId)));
    }

    /** Newest orders first, optionally one status. limit is clamped to 1..100. */
    @Transactional(readOnly = true)
    public List<OrderSummary> list(String status, int limit) {
        PageRequest page = PageRequest.of(0, Math.max(1, Math.min(limit, MAX_LIST)));
        List<BoutiqueOrder> orders;
        if (status == null || status.isBlank()) {
            orders = orderRepository.findAllByOrderByCreatedAtDescOrderIdDesc(page);
        } else {
            BoutiqueOrderStatus s;
            try {
                s = BoutiqueOrderStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new AgentApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "status must be one of " + Arrays.toString(BoutiqueOrderStatus.values()), Map.of("status", status));
            }
            orders = orderRepository.findByStatusOrderByCreatedAtDescOrderIdDesc(s, page);
        }
        return summaries(orders);
    }

    /** Paid orders waiting for the tailor's measurement call, oldest payment first. */
    @Transactional(readOnly = true)
    public List<OrderSummary> pendingMeasurementCalls(int limit) {
        return summaries(orderRepository.findByMeasurementStatusOrderByPaidAtAscOrderIdAsc(
            MEASUREMENT_PENDING, PageRequest.of(0, Math.max(1, Math.min(limit, MAX_LIST)))));
    }

    @Transactional(readOnly = true)
    public long countPendingMeasurementCalls() {
        return orderRepository.countByMeasurementStatus(MEASUREMENT_PENDING);
    }

    /** The tailor has taken her exact measurements (idempotent; only for orders that were waiting). */
    @Transactional
    public OrderResponse markMeasurementsTaken(String orderNo, String staff) {
        BoutiqueOrder order = orderRepository.findByOrderNo(orderNo.trim().toUpperCase(Locale.ROOT))
            .orElseThrow(() -> AgentApiException.notFound("ORDER_NOT_FOUND", "No order " + orderNo));
        if (order.getMeasurementStatus() == null) {
            throw AgentApiException.conflict("MEASUREMENTS_NOT_NEEDED", "This order doesn't need a measurement call");
        }
        if (MEASUREMENT_PENDING.equals(order.getMeasurementStatus())) {
            order.setMeasurementStatus(MEASUREMENT_TAKEN);
            order.setMeasurementTakenAt(LocalDateTime.now());
            order.setMeasurementTakenBy(staff);
            order.setUpdatedAt(LocalDateTime.now());
        }
        return toResponse(order);
    }

    /** A customer's orders, newest first (staff inbox side panel). */
    @Transactional(readOnly = true)
    public List<OrderSummary> listForCustomer(Integer customerId, int limit) {
        return summaries(orderRepository.findByCustomerIdOrderByCreatedAtDescOrderIdDesc(customerId,
            PageRequest.of(0, Math.max(1, Math.min(limit, MAX_LIST)))));
    }

    private List<OrderSummary> summaries(List<BoutiqueOrder> orders) {
        Map<Integer, BoutiqueOrderPayment> payments = paymentLinkService.latestByOrder(
            orders.stream().map(BoutiqueOrder::getOrderId).toList());
        Map<Integer, BoutiqueCustomer> customers = customerRepository
            .findAllById(orders.stream().map(BoutiqueOrder::getCustomerId).collect(Collectors.toSet())).stream()
            .collect(Collectors.toMap(BoutiqueCustomer::getCustomerId, Function.identity()));
        return orders.stream().map(o -> {
            BoutiqueCustomer c = customers.get(o.getCustomerId());
            BoutiqueOrderItem first = o.getItems().isEmpty() ? null : o.getItems().get(0);
            BoutiqueOrderPayment pay = payments.get(o.getOrderId());
            return new OrderSummary(o.getOrderNo(), o.getStatus().name(), o.getChannel(), o.getCustomerId(),
                c == null ? null : c.getCustomerName(),
                c == null ? null : (c.getPhoneE164() != null ? c.getPhoneE164() : c.getPhone()),
                first == null ? null : first.getDesignCode(), first == null ? null : first.getDesignName(),
                first == null ? null : first.getSizeCode(), first == null ? 0 : first.getQuantity(),
                o.getTotal(), PricingService.CURRENCY, o.getEstimatedDeliveryDate(),
                pay == null ? null : pay.getStatus(), o.getPaidAt(), pay == null ? null : pay.getRazorpayPaymentId(),
                o.getMeasurementStatus(), o.getCreatedAt(), o.getOrderType(),
                first == null ? null : first.getFabricMetres());
        }).toList();
    }

    @Transactional(readOnly = true)
    public OrderResponse get(String orderNo) {
        return orderRepository.findByOrderNo(orderNo.trim().toUpperCase(Locale.ROOT))
            .map(this::toResponse)
            .orElseThrow(() -> AgentApiException.notFound("ORDER_NOT_FOUND", "No order " + orderNo));
    }

    private Priced price(String designCode, String sizeCode, List<Integer> optionIds, int qty) {
        BoutiqueDesign design = designRepository.findByCodeIgnoreCase(designCode.trim())
            .orElseThrow(() -> AgentApiException.notFound("DESIGN_NOT_FOUND", "No design with code " + designCode));
        String size = catalogService.requireSize(sizeCode);
        List<Integer> ids = optionIds == null ? List.of() : optionIds;
        List<CustomisationOption> options = ids.isEmpty() ? List.of() : optionRepository.findAllById(ids);
        return new Priced(design, size, pricingService.price(design, ids, options, qty));
    }

    private BoutiqueOrder sameCustomer(BoutiqueOrder order, CreateOrderRequest request) {
        return sameCustomer(order, request.customerId());
    }

    /** An idempotency key belongs to one customer; reusing it for another is a client bug. */
    private BoutiqueOrder sameCustomer(BoutiqueOrder order, Integer customerId) {
        if (!order.getCustomerId().equals(customerId)) {
            throw AgentApiException.conflict("IDEMPOTENCY_KEY_REUSED",
                "This idempotency_key was already used for a different customer");
        }
        return order;
    }

    private static int leadTime(BoutiqueDesign design) {
        return design.getLeadTimeDays() == null ? 0 : design.getLeadTimeDays();
    }

    private Map<String, Object> measurementSnapshot(OrderMeasurements m) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("source", "order_request");
        snap.put("size_code", m.sizeCode() == null || m.sizeCode().isBlank() ? null : catalogService.requireSize(m.sizeCode()));
        snap.put("height_cm", m.heightCm());
        snap.put("bust_in", m.bustIn());
        snap.put("waist_in", m.waistIn());
        snap.put("hip_in", m.hipIn());
        snap.put("fit_preference", m.fitPreference() == null ? null : m.fitPreference().toUpperCase(Locale.ROOT));
        snap.put("notes", m.notes() == null || m.notes().isBlank() ? null : m.notes().trim());
        return snap;
    }

    private static Map<String, Object> measurementSnapshot(BoutiqueMeasurement m) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("source", "saved_profile");
        snap.put("measurement_id", m.getMeasurementId());
        snap.put("size_code", m.getSizeCode());
        snap.put("height_cm", m.getHeightCm());
        snap.put("bust_in", m.getBustIn());
        snap.put("waist_in", m.getWaistIn());
        snap.put("hip_in", m.getHipIn());
        snap.put("fit_preference", m.getFitPreference());
        snap.put("notes", m.getNotes());
        snap.put("recorded_at", m.getCreatedAt() == null ? null : m.getCreatedAt().toString());
        return snap;
    }

    private OrderResponse toResponse(BoutiqueOrder o) {
        List<OrderItemResponse> items = o.getItems().stream()
            .map(i -> new OrderItemResponse(i.getDesignCode(), i.getDesignName(), i.getSizeCode(),
                fromJson(i.getOptionsSnapshot(), new TypeReference<List<SelectedOptionDto>>() {}),
                i.getMeasurementsSnapshot() == null ? null
                    : fromJson(i.getMeasurementsSnapshot(), new TypeReference<Map<String, Object>>() {}),
                i.getUnitPrice(), i.getQuantity(), i.getLineTotal(), i.getFabricMetres(), i.getFabricPricePerUnit()))
            .toList();
        DeliveryDetails delivery = new DeliveryDetails(o.getDeliveryName(), o.getDeliveryPhone(),
            o.getDeliveryAddressLine1(), o.getDeliveryAddressLine2(), o.getDeliveryCity(), o.getDeliveryState(),
            o.getDeliveryPincode());
        return new OrderResponse(o.getOrderNo(), o.getStatus().name(), o.getCustomerId(), o.getChannel(), delivery,
            items, o.getSubtotal(), o.getDeliveryCharge(), o.getTotal(), PricingService.CURRENCY,
            o.getEstimatedDeliveryDate(), o.getPaidAt(),
            paymentLinkService.latestByOrder(List.of(o.getOrderId())).values().stream().findFirst()
                .map(p -> paymentLinkService.toInfo(o, p)).orElse(null),
            o.getSourceType(), o.getSourceId(), o.getCtwaClid(),
            o.getMeasurementStatus(), o.getMeasurementTakenAt(), o.getMeasurementTakenBy(),
            o.getCreatedAt(), o.getOrderType());
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise order snapshot", e);
        }
    }

    private <T> T fromJson(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt order snapshot", e);
        }
    }
}

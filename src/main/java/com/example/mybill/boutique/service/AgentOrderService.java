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
    @Autowired @org.springframework.context.annotation.Lazy
    private com.example.mybill.boutique.payment.PaymentLinkService paymentLinkService;

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
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
                o.getCreatedAt());
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
                i.getUnitPrice(), i.getQuantity(), i.getLineTotal()))
            .toList();
        DeliveryDetails delivery = new DeliveryDetails(o.getDeliveryName(), o.getDeliveryPhone(),
            o.getDeliveryAddressLine1(), o.getDeliveryAddressLine2(), o.getDeliveryCity(), o.getDeliveryState(),
            o.getDeliveryPincode());
        return new OrderResponse(o.getOrderNo(), o.getStatus().name(), o.getCustomerId(), o.getChannel(), delivery,
            items, o.getSubtotal(), o.getDeliveryCharge(), o.getTotal(), PricingService.CURRENCY,
            o.getEstimatedDeliveryDate(), o.getPaidAt(),
            paymentLinkService.latestByOrder(List.of(o.getOrderId())).values().stream().findFirst()
                .map(p -> paymentLinkService.toInfo(o, p)).orElse(null),
            o.getCreatedAt());
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

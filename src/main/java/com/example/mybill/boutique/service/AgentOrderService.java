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

import java.time.LocalDateTime;
import java.util.*;

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

    public record CreateResult(OrderResponse order, boolean created) {}

    private record Priced(BoutiqueDesign design, String sizeCode, PriceBreakdown breakdown) {}

    /** Price breakdown for a design + size + options; nothing is saved. */
    @Transactional(readOnly = true)
    public QuoteResponse quote(QuoteRequest request) {
        Priced p = price(request.designCode(), request.sizeCode(), request.optionIds(),
            request.qty() == null ? 1 : request.qty());
        return new QuoteResponse(p.design().getCode(), p.design().displayName(), p.sizeCode(),
            p.design().getLeadTimeDays(), p.breakdown());
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
        order.setCreatedAt(now);
        order.setUpdatedAt(now);

        BoutiqueOrderItem item = new BoutiqueOrderItem();
        item.setDesignId(p.design().getDesignId());
        item.setDesignCode(p.design().getCode());
        item.setDesignName(p.design().displayName());
        item.setSizeCode(p.sizeCode());
        item.setOptionsSnapshot(toJson(b.options()));
        item.setMeasurementsSnapshot(customerService.findLatestMeasurement(request.customerId())
            .map(m -> toJson(measurementSnapshot(m))).orElse(null));
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

    private static Map<String, Object> measurementSnapshot(BoutiqueMeasurement m) {
        Map<String, Object> snap = new LinkedHashMap<>();
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
            items, o.getSubtotal(), o.getDeliveryCharge(), o.getTotal(), PricingService.CURRENCY, o.getCreatedAt());
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

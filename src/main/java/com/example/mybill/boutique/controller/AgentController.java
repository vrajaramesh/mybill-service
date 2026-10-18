package com.example.mybill.boutique.controller;

import com.example.mybill.boutique.dto.*;
import com.example.mybill.boutique.payment.PaymentLinkService;
import com.example.mybill.boutique.service.AgentCatalogService;
import com.example.mybill.boutique.service.AgentCustomerService;
import com.example.mybill.boutique.service.AgentOrderService;
import com.example.mybill.boutique.service.MarketingService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Internal API for the srisa-agent service. Authenticated by AgentAuthFilter (Google ID token of the agent's
 * service account) and always scoped to the configured agent tenant. Never used by the UI.
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    @Autowired private AgentCatalogService catalogService;
    @Autowired private AgentCustomerService customerService;
    @Autowired private AgentOrderService orderService;
    @Autowired private PaymentLinkService paymentLinkService;
    @Autowired private MarketingService marketingService;

    /** Newest active designs, e.g. ?limit=4 (1..20). Declared before {code} for readability; Spring prefers literals. */
    @GetMapping("/designs/latest")
    public List<DesignSummaryResponse> latestDesigns(@RequestParam(defaultValue = "4") int limit) {
        return catalogService.latest(limit);
    }

    @GetMapping("/designs/{code}")
    public DesignDetailResponse design(@PathVariable String code) {
        return catalogService.getDesign(code);
    }

    @GetMapping("/size-chart")
    public List<SizeChartRow> sizeChart() {
        return catalogService.sizeChart();
    }

    /** 201 when a new customer was created, 200 when an existing one was found (and possibly enriched). */
    @PostMapping("/customers/upsert")
    public ResponseEntity<CustomerResponse> upsertCustomer(@Valid @RequestBody CustomerUpsertRequest request) {
        AgentCustomerService.UpsertResult result = customerService.upsert(request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.customer());
    }

    @GetMapping("/customers/{id}/measurements")
    public MeasurementResponse latestMeasurements(@PathVariable Integer id) {
        return customerService.latestMeasurement(id);
    }

    @PostMapping("/customers/{id}/measurements")
    public ResponseEntity<MeasurementResponse> saveMeasurements(@PathVariable Integer id,
                                                                @Valid @RequestBody MeasurementRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(customerService.saveMeasurement(id, request));
    }

    @PostMapping("/orders/quote")
    public QuoteResponse quote(@Valid @RequestBody QuoteRequest request) {
        return orderService.quote(request);
    }

    /** 201 for a new order; 200 with the original order when the idempotency_key was already used. */
    @PostMapping("/orders")
    public ResponseEntity<OrderResponse> createOrder(@Valid @RequestBody CreateOrderRequest request) {
        try {
            AgentOrderService.CreateResult result = orderService.create(request);
            return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.order());
        } catch (DataIntegrityViolationException e) {
            // Lost a race with a concurrent request carrying the same idempotency_key: return the winner's order.
            return orderService.findByIdempotencyKey(request.idempotencyKey(), request.customerId())
                .map(ResponseEntity::ok)
                .orElseThrow(() -> e);
        }
    }

    /** Recent orders for staff/admin use and testing, e.g. ?status=DRAFT&limit=20 (limit 1..100). */
    @GetMapping("/orders")
    public List<OrderSummary> orders(@RequestParam(required = false) String status,
                                     @RequestParam(defaultValue = "20") int limit) {
        return orderService.list(status, limit);
    }

    /** Razorpay Payment Link for the order: the active one, or a new one (amount = order total from the DB). */
    @PostMapping("/orders/{orderNo}/payment-link")
    public PaymentInfo paymentLink(@PathVariable String orderNo) {
        return paymentLinkService.createOrGetLink(orderNo);
    }

    @GetMapping("/orders/{orderNo}/payment")
    public PaymentInfo payment(@PathVariable String orderNo) {
        return paymentLinkService.latestPayment(orderNo);
    }

    @GetMapping("/orders/{orderNo}")
    public OrderResponse order(@PathVariable String orderNo) {
        return orderService.get(orderNo);
    }

    /** Design promoted by a Click-to-WhatsApp ad / Instagram post, e.g. ?type=AD&id=1202... (active links only). */
    @GetMapping("/marketing-links/resolve")
    public MarketingLinkResolveResponse resolveMarketingLink(@RequestParam String type, @RequestParam String id) {
        return marketingService.resolve(type, id);
    }

    /** 201 for a new event; 200 with the original when the ctwa_clid was already reported. */
    @PostMapping("/marketing-events")
    public ResponseEntity<MarketingEventResponse> marketingEvent(@Valid @RequestBody MarketingEventRequest request) {
        MarketingEventResponse result = marketingService.recordEvent(request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result);
    }
}

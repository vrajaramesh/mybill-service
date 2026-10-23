package com.example.mybill.boutique.controller;

import com.example.mybill.boutique.dto.*;
import com.example.mybill.boutique.payment.PaymentLinkService;
import com.example.mybill.boutique.service.AgentCatalogService;
import com.example.mybill.boutique.service.AgentCustomerService;
import com.example.mybill.boutique.service.AgentOrderService;
import com.example.mybill.boutique.service.FabricCatalogService;
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
    @Autowired private FabricCatalogService fabricCatalog;
    @Autowired private com.example.mybill.boutique.service.CollectionService collectionService;
    @Autowired private com.example.mybill.boutique.service.DailyFiguresService dailyFigures;

    /** Newest active designs, e.g. ?limit=4 (1..20). Declared before {code} for readability; Spring prefers literals. */
    @GetMapping("/designs/latest")
    public List<DesignSummaryResponse> latestDesigns(@RequestParam(defaultValue = "4") int limit) {
        return catalogService.latest(limit);
    }

    @GetMapping("/designs/{code}")
    public DesignDetailResponse design(@PathVariable String code) {
        return catalogService.getDesign(code);
    }

    /** In-stock fabrics sold by the metre matching q (words of the name/category/tags, or a FAB-&lt;id&gt; code). */
    @GetMapping("/fabrics")
    public List<FabricResponse> fabrics(@RequestParam(defaultValue = "") String q,
                                        @RequestParam(defaultValue = "5") int limit) {
        return fabricCatalog.search(q, limit);
    }

    /** One fabric by code (FAB-6223) or product id, including when it is out of stock. */
    @GetMapping("/fabrics/{code}")
    public FabricResponse fabric(@PathVariable String code) {
        Integer id = FabricCatalogService.parseCode(code);
        if (id == null) throw com.example.mybill.boutique.exception.AgentApiException.notFound("FABRIC_NOT_FOUND", "No fabric " + code);
        return fabricCatalog.require(id);
    }

    /** One IST day's order/payment/website figures for the daily AI report, e.g. ?date=2026-10-21. */
    @GetMapping("/reports/daily")
    public java.util.Map<String, Object> dailyFigures(@RequestParam String date) {
        return dailyFigures.figures(java.time.LocalDate.parse(date));
    }

    /** A fabric / readymade collection with its items (codes, colours, prices, stock, sizes, photos, instructions). */
    @GetMapping("/collections/{code}")
    public java.util.Map<String, Object> collection(@PathVariable String code) {
        return collectionService.get(code);
    }

    /** One collection item by its code (e.g. MCS-1B), with its collection. */
    @GetMapping("/collection-items/{code}")
    public java.util.Map<String, Object> collectionItem(@PathVariable String code) {
        return collectionService.item(code);
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

    /** Her answer to WhatsApp updates/offers (latest wins); stored with the time. */
    @PostMapping("/customers/{id}/consent")
    public CustomerResponse consent(@PathVariable Integer id, @Valid @RequestBody ConsentRequest request) {
        return customerService.setConsent(id, request.whatsappUpdates());
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

    /** Goods cart price: [{item_code | product_id, quantity (metres / pieces), size}], pincode. */
    @PostMapping("/orders/goods/quote")
    public GoodsQuoteResponse goodsQuote(@Valid @RequestBody GoodsQuoteRequest request) {
        return orderService.goodsQuote(request);
    }

    /** Goods order (fabric / readymade lines): 201 new, 200 with the original order on a repeated idempotency_key. */
    @PostMapping("/orders/goods")
    public ResponseEntity<OrderResponse> createGoodsOrder(@Valid @RequestBody CreateGoodsOrderRequest request) {
        try {
            AgentOrderService.CreateResult result = orderService.createGoods(request);
            return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.order());
        } catch (DataIntegrityViolationException e) {
            return orderService.findByIdempotencyKey(request.idempotencyKey(), request.customerId())
                .map(ResponseEntity::ok)
                .orElseThrow(() -> e);
        }
    }

    @PostMapping("/orders/fabric/quote")
    public FabricQuoteResponse fabricQuote(@Valid @RequestBody FabricQuoteRequest request) {
        return orderService.fabricQuote(request);
    }

    /** Fabric-only order: 201 new, 200 with the original order when the idempotency_key was already used. */
    @PostMapping("/orders/fabric")
    public ResponseEntity<OrderResponse> createFabricOrder(@Valid @RequestBody CreateFabricOrderRequest request) {
        try {
            AgentOrderService.CreateResult result = orderService.createFabric(request);
            return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.order());
        } catch (DataIntegrityViolationException e) {
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

    /**
     * Design promoted by a Click-to-WhatsApp ad / Instagram post (active links only):
     * ?type=AD&id=1202... or ?type=IG_MEDIA&permalink=https://www.instagram.com/p/...
     */
    @GetMapping("/marketing-links/resolve")
    public MarketingLinkResolveResponse resolveMarketingLink(@RequestParam String type,
                                                             @RequestParam(required = false) String id,
                                                             @RequestParam(required = false) String permalink) {
        return marketingService.resolve(type, id, permalink);
    }

    /** Auto-links Instagram posts whose caption names a design (never touches links staff created). */
    @PostMapping("/marketing-links/sync-ig-media")
    public IgMediaSyncResponse syncIgMedia(@RequestBody List<IgMediaItem> items) {
        return marketingService.syncIgMedia(items);
    }

    /** 201 for a new event; 200 with the original when the ctwa_clid was already reported. */
    @PostMapping("/marketing-events")
    public ResponseEntity<MarketingEventResponse> marketingEvent(@Valid @RequestBody MarketingEventRequest request) {
        MarketingEventResponse result = marketingService.recordEvent(request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result);
    }
}

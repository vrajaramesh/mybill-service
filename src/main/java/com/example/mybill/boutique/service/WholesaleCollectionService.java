package com.example.mybill.boutique.service;

import com.example.mybill.boutique.dto.GoodsLineResponse;
import com.example.mybill.boutique.dto.PriceBreakdown;
import com.example.mybill.boutique.entity.BoutiqueOrder;
import com.example.mybill.boutique.exception.AgentApiException;
import com.example.mybill.wholesale.dto.WholesaleCustomerRequest;
import com.example.mybill.wholesale.dto.WholesaleQuotationItemRequest;
import com.example.mybill.wholesale.dto.WholesaleQuotationRequest;
import com.example.mybill.wholesale.dto.WholesaleQuotationResponse;
import com.example.mybill.wholesale.entity.WholesaleCustomer;
import com.example.mybill.wholesale.repository.WholesaleCustomerRepository;
import com.example.mybill.wholesale.service.GstStates;
import com.example.mybill.wholesale.service.WholesaleCustomerService;
import com.example.mybill.wholesale.service.WholesalePriceLookupService;
import com.example.mybill.wholesale.service.WholesaleQuotationService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * WHOLESALE collections (db/boutique/V17): the bridge between Boutique collections and the Wholesale module.
 * Items point to wholesale_products; their price slabs, minimum order, GST %, stock, photos and AI instructions come
 * from there. A cart's slab and minimum order apply to the TOTAL quantity of each collection (MCS-1 20 m + MCS-2 30 m =
 * 50 m). Prices exclude GST; transport is added by the team in the final quotation. A confirmed cart becomes a quotation
 * DRAFT in the Wholesale module (the buyer is found by phone or registered as a wholesale customer).
 */
@Service
public class WholesaleCollectionService {

    public static final String WHOLESALE = "WHOLESALE";
    private static final Logger log = LoggerFactory.getLogger(WholesaleCollectionService.class);

    @PersistenceContext private EntityManager em;
    @Autowired private WholesalePriceLookupService priceLookup;
    @Autowired private WholesaleQuotationService quotationService;
    @Autowired private WholesaleCustomerService customerService;
    @Autowired private WholesaleCustomerRepository customerRepository;

    /** A wholesale product as collections need it. imageUrl = its first photo. */
    public record Product(int id, String code, String name, String unit, BigDecimal available, BigDecimal gstPct,
                          boolean active, String aiInstructions, String description, String imageUrl) {}

    /** One cart line of a wholesale collection item. */
    public record Line(String itemCode, String colour, String collectionCode, int productId, BigDecimal quantity) {}

    /** What the cart adds up to: GST estimate (intra-state; the total is the same inter-state). */
    @com.fasterxml.jackson.databind.annotation.JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Totals(BigDecimal subtotal, BigDecimal gst, BigDecimal totalWithGst, List<Map<String, Object>> collections) {}

    // ── catalog ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Map<Integer, Product> products(Collection<Integer> ids) {
        Map<Integer, Product> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        for (Object r : em.createNativeQuery("""
                SELECT p.wholesale_product_id, p.product_code, p.product_name, p.unit, p.available_quantity, p.gst_pct,
                       p.is_active, p.ai_instructions, p.description,
                       (SELECT i.image_url FROM wholesale_product_images i WHERE i.wholesale_product_id = p.wholesale_product_id
                         ORDER BY i.sort_order, i.image_id LIMIT 1)
                  FROM wholesale_products p WHERE p.wholesale_product_id IN (?1)""").setParameter(1, ids).getResultList()) {
            Object[] a = (Object[]) r;
            out.put(((Number) a[0]).intValue(), new Product(((Number) a[0]).intValue(), (String) a[1], (String) a[2], (String) a[3],
                a[4] == null ? BigDecimal.ZERO : new BigDecimal(a[4].toString()),
                a[5] == null ? BigDecimal.ZERO : new BigDecimal(a[5].toString()),
                Boolean.TRUE.equals(a[6]), (String) a[7], (String) a[8], (String) a[9]));
        }
        return out;
    }

    /** Active wholesale products for the staff item picker: [{wholesale_product_id, code, name, unit}]. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> pickList() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object r : em.createNativeQuery("""
                SELECT wholesale_product_id, product_code, product_name, unit FROM wholesale_products
                 WHERE is_active = TRUE ORDER BY product_name""").getResultList()) {
            Object[] a = (Object[]) r;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("wholesale_product_id", a[0]);
            m.put("code", a[1]);
            m.put("name", a[2]);
            m.put("unit", a[3]);
            out.add(m);
        }
        return out;
    }

    /** Today's slabs and minimum order of a product: {unit, gst_pct, minimum_order, price_slabs: [...]}. */
    public Map<String, Object> pricing(Product p) {
        WholesalePriceLookupService.PriceInfo info = priceLookup.lookup(p.id(), p.unit(), null, null);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("unit", p.unit());
        m.put("gst_pct", plain(p.gstPct()));
        m.put("minimum_order", info.minimumOrderQuantity());
        m.put("price_slabs", info.slabs().stream().map(s -> {
            Map<String, Object> slab = new LinkedHashMap<>();
            slab.put("from", s.minQuantity());
            slab.put("to", s.maxQuantity());
            slab.put("price_per_unit", s.pricePerUnit());
            if (s.gstPct() != null) slab.put("gst_pct", plain(s.gstPct()));
            return slab;
        }).toList());
        return m;
    }

    // ── cart pricing ─────────────────────────────────────────────────────────

    /**
     * Prices wholesale lines: per collection, the total quantity must reach each of its products' minimum order and
     * selects the slab; per product, the cart's total must be in stock. Lines keep their own quantities.
     */
    public GoodsService.Priced price(List<Line> lines) {
        Map<Integer, Product> products = products(lines.stream().map(Line::productId).collect(Collectors.toSet()));
        Map<String, BigDecimal> perCollection = new LinkedHashMap<>();
        Map<Integer, BigDecimal> perProduct = new HashMap<>();
        for (Line l : lines) {
            Product p = products.get(l.productId());
            if (p == null || !p.active()) {
                throw err("PRODUCT_UNAVAILABLE", l.itemCode() + " is not available right now", Map.of("code", l.itemCode()));
            }
            BigDecimal q = l.quantity();
            if (q == null || q.signum() <= 0 || q.stripTrailingZeros().scale() > 2) {
                throw err("INVALID_QUANTITY", "Quantity of " + l.itemCode() + " must be more than 0 (at most 2 decimals)",
                    Map.of("code", l.itemCode()));
            }
            perCollection.merge(l.collectionCode(), q, BigDecimal::add);
            perProduct.merge(l.productId(), q, BigDecimal::add);
        }
        for (Map.Entry<Integer, BigDecimal> e : perProduct.entrySet()) {
            Product p = products.get(e.getKey());
            if (e.getValue().compareTo(p.available()) > 0) {
                throw err("INSUFFICIENT_STOCK", "We don't have " + plain(e.getValue()).toPlainString() + " " + p.unit()
                    + " of " + p.name() + " in stock right now", Map.of("product", p.name(),
                    "hint", "Offer a smaller quantity or let our team check (request_human_help); never state the stock figure."));
            }
        }
        // rate per (collection, product), from that product's slab for the collection's total
        Map<String, BigDecimal[]> rates = new HashMap<>();   // "COLL|product" -> {rate, gstPct}
        List<Map<String, Object>> collections = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> c : perCollection.entrySet()) {
            BigDecimal total = c.getValue();
            Set<Integer> ids = lines.stream().filter(l -> l.collectionCode().equals(c.getKey())).map(Line::productId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
            String unit = null;
            for (Integer id : ids) {
                Product p = products.get(id);
                unit = p.unit();
                WholesalePriceLookupService.PriceInfo info = priceLookup.lookup(id, p.unit(), total, null);
                if ("BELOW_MINIMUM".equals(info.requestedStatus())) {
                    throw err("BELOW_MINIMUM_ORDER", "The minimum order for " + c.getKey() + " is "
                        + info.minimumOrderQuantity().toPlainString() + " " + p.unit() + " in total (now "
                        + plain(total).toPlainString() + " " + p.unit() + ")",
                        Map.of("collection", c.getKey(), "minimum_order", info.minimumOrderQuantity(), "unit", p.unit(),
                            "cart_total", plain(total), "hint", "The codes of one collection add up towards the minimum."));
                }
                if (!"PRICED".equals(info.requestedStatus()) || info.requested() == null) {
                    log.warn("WHOLESALE_NO_PRICE product={} quantity={} status={}", id, total, info.requestedStatus());
                    throw err("NO_PRICE_FOR_QUANTITY", "We don't have a listed rate for " + plain(total).toPlainString()
                        + " " + p.unit() + " of " + p.name(), Map.of("collection", c.getKey(),
                        "hint", "Our team gives special rates for this quantity: call request_human_help."));
                }
                rates.put(c.getKey() + "|" + id, new BigDecimal[]{info.requested().pricePerUnit(), info.requested().gstPct()});
            }
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("collection", c.getKey());
            cm.put("total_quantity", plain(total));
            cm.put("unit", unit);
            collections.add(cm);
        }
        List<GoodsLineResponse> out = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO, gst = BigDecimal.ZERO;
        for (Line l : lines) {
            Product p = products.get(l.productId());
            BigDecimal[] rate = rates.get(l.collectionCode() + "|" + l.productId());
            BigDecimal lineTotal = PricingService.money(rate[0].multiply(l.quantity()));
            subtotal = subtotal.add(lineTotal);
            gst = gst.add(lineTotal.multiply(rate[1]).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP));
            out.add(new GoodsLineResponse(l.itemCode(), p.id(), p.name(), l.colour(), WHOLESALE, p.unit(), null,
                plain(l.quantity()), PricingService.money(rate[0]), lineTotal));
        }
        subtotal = PricingService.money(subtotal);
        gst = PricingService.money(gst);
        BigDecimal withGst = PricingService.money(subtotal.add(gst));
        PriceBreakdown b = new PriceBreakdown(subtotal, List.of(), PricingService.money(BigDecimal.ZERO), subtotal, 1, subtotal,
            PricingService.money(BigDecimal.ZERO), null, withGst, PricingService.CURRENCY);
        return new GoodsService.Priced(out, b, BoutiqueOrder.TYPE_WHOLESALE, null, new Totals(subtotal, gst, withGst, collections));
    }

    // ── confirmed cart -> quotation draft ────────────────────────────────────

    /**
     * Saves a quotation DRAFT for a confirmed wholesale order (lines of one product in one collection become one
     * quotation line at the collection's slab rate) and links it to the order. phone = the buyer's WhatsApp number.
     */
    @Transactional
    public void createQuotation(BoutiqueOrder order, GoodsService.Priced priced, String phone, String staffName) {
        WholesaleCustomer customer = findOrRegister(order, phone);
        Map<String, List<GoodsLineResponse>> groups = new LinkedHashMap<>();
        Map<String, String> collectionOf = itemCollections(priced.lines());
        for (GoodsLineResponse l : priced.lines()) {
            groups.computeIfAbsent(collectionOf.getOrDefault(l.itemCode(), "") + "|" + l.productId(), k -> new ArrayList<>()).add(l);
        }
        List<WholesaleQuotationItemRequest> items = new ArrayList<>();
        for (List<GoodsLineResponse> g : groups.values()) {
            BigDecimal qty = g.stream().map(GoodsLineResponse::quantity).reduce(BigDecimal.ZERO, BigDecimal::add);
            String desc = g.stream().map(l -> l.itemCode() + (l.colour() != null && !l.colour().isBlank() ? " " + l.colour() : "")
                + " " + plain(l.quantity()).toPlainString() + " " + l.unit()).collect(Collectors.joining(", "));
            items.add(new WholesaleQuotationItemRequest(g.get(0).productId(), qty, g.get(0).unitPrice(), null, null,
                desc.length() > 500 ? desc.substring(0, 500) : desc));
        }
        String address = String.join(", ", java.util.stream.Stream.of(order.getDeliveryName(), order.getDeliveryAddressLine1(),
                order.getDeliveryAddressLine2(), order.getDeliveryCity(), order.getDeliveryState() + " " + order.getDeliveryPincode())
            .filter(v -> v != null && !v.isBlank()).toList());
        String notes = "WhatsApp order " + order.getOrderNo() + " (" + order.getChannel().toLowerCase(Locale.ROOT)
            + "). Ship to: " + address + ", phone " + order.getDeliveryPhone() + ". Add transport charges if any.";
        WholesaleQuotationRequest request = new WholesaleQuotationRequest(customer.getWholesaleCustomerId(),
            java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")), null, null, null, null, notes, null, items);
        WholesaleQuotationResponse q = quotationService.create(request, staffName);
        order.setWholesaleCustomerId(customer.getWholesaleCustomerId());
        order.setWholesaleQuotationId(q.quotationId());
        log.info("WHOLESALE_QUOTATION_DRAFT order={} quotation={} customer={}", order.getOrderNo(), q.quotationId(),
            customer.getWholesaleCustomerId());
    }

    /** The one active wholesale customer with this phone (last 10 digits), else a new one from the delivery details. */
    private WholesaleCustomer findOrRegister(BoutiqueOrder order, String phone) {
        String digits = (phone == null || phone.isBlank() ? order.getDeliveryPhone() : phone).replaceAll("\\D", "");
        String last10 = digits.length() >= 10 ? digits.substring(digits.length() - 10) : digits;
        if (last10.length() == 10) {
            List<WholesaleCustomer> found = customerRepository.findByPhoneEndingWith(last10).stream()
                .filter(c -> Boolean.TRUE.equals(c.getIsActive())).toList();
            if (found.size() == 1) return found.get(0);
            if (found.size() > 1) log.warn("WHOLESALE_CUSTOMER_AMBIGUOUS phone=…{} matches={} (registering the order's buyer anew)",
                last10.substring(6), found.size());
        }
        String pin = order.getDeliveryPincode() != null && order.getDeliveryPincode().matches("[1-9][0-9]{5}") ? order.getDeliveryPincode() : null;
        String address = String.join(", ", java.util.stream.Stream.of(order.getDeliveryAddressLine1(), order.getDeliveryAddressLine2())
            .filter(v -> v != null && !v.isBlank()).toList());
        String name = order.getDeliveryName() == null ? "WhatsApp buyer" : order.getDeliveryName().trim();
        WholesaleCustomerRequest request = new WholesaleCustomerRequest(name.length() > 150 ? name.substring(0, 150) : name,
            null, null, null, last10.length() == 10 ? "+91" + last10 : null, null, address, truncate(order.getDeliveryCity(), 100),
            stateCode(order.getDeliveryState()), pin, true, null, null, null, null,
            "Registered from WhatsApp order " + order.getOrderNo() + " - add the GSTIN / business name if they have one.", true);
        Integer id = customerService.create(request).wholesaleCustomerId();
        log.info("WHOLESALE_CUSTOMER_REGISTERED customer={} order={}", id, order.getOrderNo());
        return customerRepository.findById(id).orElseThrow();
    }

    /** item code -> collection code for the given lines. */
    private Map<String, String> itemCollections(List<GoodsLineResponse> lines) {
        Set<String> codes = lines.stream().map(GoodsLineResponse::itemCode).filter(Objects::nonNull)
            .map(c -> c.toUpperCase(Locale.ROOT)).collect(Collectors.toSet());
        Map<String, String> out = new HashMap<>();
        if (codes.isEmpty()) return out;
        for (Object r : em.createNativeQuery("""
                SELECT i.item_code, c.code FROM boutique_collection_items i JOIN boutique_collections c ON c.collection_id = i.collection_id
                 WHERE UPPER(i.item_code) IN (?1)""").setParameter(1, codes).getResultList()) {
            Object[] a = (Object[]) r;
            out.put((String) a[0], (String) a[1]);
        }
        return out;
    }

    /** GST state code for a state name ("Andhra Pradesh" -> "37"), or null. */
    static String stateCode(String state) {
        if (state == null || state.isBlank()) return null;
        String wanted = state.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        return GstStates.all().entrySet().stream()
            .filter(e -> e.getValue().toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "").equals(wanted))
            .map(Map.Entry::getKey).findFirst().orElse(null);
    }

    static BigDecimal plain(BigDecimal v) {
        BigDecimal s = v.stripTrailingZeros();
        return s.scale() < 0 ? s.setScale(0) : s;
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static AgentApiException err(String code, String message, Map<String, Object> details) {
        return AgentApiException.unprocessable(code, message, details);
    }
}

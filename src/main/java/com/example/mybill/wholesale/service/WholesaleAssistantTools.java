package com.example.mybill.wholesale.service;

import com.example.mybill.wholesale.dto.WholesaleQuotationItemRequest;
import com.example.mybill.wholesale.dto.WholesaleQuotationRequest;
import com.example.mybill.wholesale.dto.WholesaleQuotationResponse;
import com.example.mybill.wholesale.entity.WholesaleAssistantSettings;
import com.example.mybill.wholesale.entity.WholesaleBusinessProfile;
import com.example.mybill.wholesale.entity.WholesaleCustomer;
import com.example.mybill.wholesale.exception.WholesaleException;
import com.example.mybill.wholesale.repository.WholesaleBusinessProfileRepository;
import com.example.mybill.wholesale.repository.WholesaleCustomerRepository;
import com.example.mybill.wholesale.service.WholesalePriceLookupService.PriceInfo;
import com.example.mybill.wholesale.service.WholesaleProductLookupService.Candidate;
import com.example.mybill.wholesale.service.WholesaleProductLookupService.ProductInfo;
import com.example.mybill.wholesale.service.WholesaleStockLookupService.StockInfo;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/**
 * The controlled backend tools of the wholesale AI assistant. This is the ONLY way the model reaches business data:
 * every tool is a fixed, validated backend call; reads are read-only, and the single write
 * ({@code create_quotation_draft}) only saves a DRAFT quotation for the conversation's own mapped customer
 * (issued automatically only when the business enabled it). The model never sees SQL, IDs of other customers,
 * purchase rates or suppliers.
 */
@Component
public class WholesaleAssistantTools {

    public static final String SEND_REPLY = "send_reply";

    /** Per-turn state shared by the tools: who is asking, the conversation context and what the tools did. */
    public static class Session {
        final Integer customerId;
        final boolean dryRun;
        final String actor;
        final WholesaleAssistantSettings settings;
        /** productId, productName, unit, quantity, quantityUnit, quotationId, quotationRef, quotationKey, quotationAt */
        final Map<String, Object> context;
        final List<Map<String, Object>> calls = new ArrayList<>();
        final List<String> attention = new ArrayList<>();
        Map<String, Object> quotation;

        public Session(Integer customerId, boolean dryRun, String actor, WholesaleAssistantSettings settings,
                       Map<String, Object> context) {
            this.customerId = customerId;
            this.dryRun = dryRun;
            this.actor = actor;
            this.settings = settings;
            this.context = context == null ? new LinkedHashMap<>() : new LinkedHashMap<>(context);
        }
    }

    @Autowired private WholesaleProductLookupService productLookup;
    @Autowired private WholesalePriceLookupService priceLookup;
    @Autowired private WholesaleStockLookupService stockLookup;
    @Autowired private WholesaleBusinessProfileRepository profileRepository;
    @Autowired private WholesaleCustomerRepository customerRepository;
    @Autowired private WholesaleQuotationService quotationService;

    private final ObjectMapper json = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    // ── Definitions ────────────────────────────────────────────────────────────────────────────────

    public List<WholesaleLlmClient.Tool> definitions() {
        List<WholesaleLlmClient.Tool> tools = new ArrayList<>();
        tools.add(tool("search_wholesale_products",
            "Find wholesale products by what the customer wrote (name, code, fabric, type, colour or design). Returns "
                + "candidates with productId. Use before any other product tool when the product is not in the "
                + "conversation context. Pass an empty query to list what we sell.",
            "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\",\"description\":\"Product wording, corrected for obvious typos\"}},\"required\":[\"query\"]}"));
        tools.add(tool("get_wholesale_product",
            "Full customer-facing details of one product: type, fabric type, material, description, available colours, "
                + "available designs, specifications, unit, HSN and GST %. Missing fields mean the information is not available.",
            "{\"type\":\"object\",\"properties\":{\"productId\":{\"type\":\"integer\"}},\"required\":[\"productId\"]}"));
        tools.add(tool("get_wholesale_price",
            "Wholesale price for a quantity (from the quantity price slabs valid today): price per unit excluding GST, "
                + "GST %, taxable amount, GST amount and total. Also returns all slabs and the minimum order quantity. "
                + "Without quantity, returns only the slabs.",
            "{\"type\":\"object\",\"properties\":{\"productId\":{\"type\":\"integer\"},\"quantity\":{\"type\":\"number\"},"
                + "\"unit\":{\"type\":\"string\",\"description\":\"Unit the customer used, e.g. meters, kg\"}},\"required\":[\"productId\"]}"));
        tools.add(tool("get_wholesale_stock",
            "Whether the product is in stock and, with quantity, whether that quantity is available now.",
            "{\"type\":\"object\",\"properties\":{\"productId\":{\"type\":\"integer\"},\"quantity\":{\"type\":\"number\"}},\"required\":[\"productId\"]}"));
        tools.add(tool("get_wholesale_minimum_quantity",
            "Minimum order quantity of a product (the smallest quantity slab) and its unit.",
            "{\"type\":\"object\",\"properties\":{\"productId\":{\"type\":\"integer\"}},\"required\":[\"productId\"]}"));
        tools.add(tool("get_wholesale_business_profile",
            "Business information: name, address, contact, GSTIN, business hours, ordering process, shipping / "
                + "delivery information, payment terms and other notes. Missing fields mean not available.",
            "{\"type\":\"object\",\"properties\":{}}"));
        tools.add(tool("get_customer_details",
            "Details of the customer in this conversation if staff linked them to a wholesale customer (name, business, "
                + "city, state, GSTIN). linked=false means unknown customer.",
            "{\"type\":\"object\",\"properties\":{}}"));
        tools.add(tool("create_quotation_draft",
            "Prepare a quotation for the customer. Call ONLY when the customer explicitly asks for a quotation / estimate "
                + "and the products and quantities are clear. Prices come from the price slabs automatically. Saves a DRAFT "
                + "that staff review and issue (unless the business enabled automatic issue).",
            "{\"type\":\"object\",\"properties\":{\"items\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":20,\"items\":{\"type\":\"object\","
                + "\"properties\":{\"productId\":{\"type\":\"integer\"},\"quantity\":{\"type\":\"number\"}},\"required\":[\"productId\",\"quantity\"]}},"
                + "\"notes\":{\"type\":\"string\",\"description\":\"Customer requirements to pass to staff, e.g. colours\"}},\"required\":[\"items\"]}"));
        tools.add(tool(SEND_REPLY,
            "Send the final reply to the customer. Always end with exactly one call to this tool.",
            "{\"type\":\"object\",\"properties\":{"
                + "\"reply\":{\"type\":\"string\",\"description\":\"Plain-text message for the customer\"},"
                + "\"intent\":{\"type\":\"string\",\"enum\":[\"PRODUCT\",\"AVAILABILITY\",\"PRICE\",\"MINIMUM_QUANTITY\",\"STOCK\","
                + "\"HSN_GST\",\"SPECIFICATIONS\",\"COLORS_DESIGNS\",\"ORDERING\",\"SHIPPING\",\"PAYMENT\",\"BUSINESS_INFO\","
                + "\"QUOTATION\",\"GREETING\",\"OTHER\"]},"
                + "\"answered\":{\"type\":\"boolean\",\"description\":\"false if any part of the question could not be answered from tool results\"},"
                + "\"confidence\":{\"type\":\"string\",\"enum\":[\"high\",\"low\"]},"
                + "\"needsHuman\":{\"type\":\"boolean\",\"description\":\"true if staff should follow up (order, complaint, unanswered question)\"},"
                + "\"reason\":{\"type\":\"string\",\"description\":\"Short note for staff when needsHuman or not answered\"}},"
                + "\"required\":[\"reply\",\"intent\",\"answered\",\"confidence\",\"needsHuman\"]}"));
        return tools;
    }

    // ── Execution ──────────────────────────────────────────────────────────────────────────────────

    /** Runs one tool call and records it in the session. Never throws: errors become {"error": "..."} results. */
    public Map<String, Object> execute(String name, JsonNode input, Session session) {
        Map<String, Object> result;
        try {
            result = switch (name) {
                case "search_wholesale_products" -> searchProducts(input.path("query").asText(""), session);
                case "get_wholesale_product" -> getProduct(intArg(input, "productId"), session);
                case "get_wholesale_price" -> getPrice(intArg(input, "productId"), decimalArg(input, "quantity"),
                    textArg(input, "unit"), session);
                case "get_wholesale_stock" -> getStock(intArg(input, "productId"), decimalArg(input, "quantity"), session);
                case "get_wholesale_minimum_quantity" -> getMinimumQuantity(intArg(input, "productId"), session);
                case "get_wholesale_business_profile" -> getBusinessProfile(session);
                case "get_customer_details" -> getCustomerDetails(session);
                case "create_quotation_draft" -> createQuotationDraft(input, session);
                default -> Map.of("error", "Unknown tool " + name);
            };
        } catch (IllegalArgumentException e) {
            result = Map.of("error", e.getMessage());
        } catch (Exception e) {
            result = Map.of("error", "Lookup failed: " + e.getMessage());
        }
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("tool", name);
        call.put("input", json.convertValue(input, new TypeReference<Map<String, Object>>() {}));
        call.put("result", result);
        session.calls.add(call);
        return result;
    }

    Map<String, Object> searchProducts(String query, Session session) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (query.isBlank()) {
            List<Map<String, Object>> all = new ArrayList<>();
            for (ProductInfo p : productLookup.activeProducts()) {
                if (all.size() >= 30) break;
                all.add(candidate(p, session));
            }
            out.put("products", all);
            return out;
        }
        List<Candidate> found = productLookup.search(query, 6);
        if (found.isEmpty()) {
            out.put("match", "NOT_FOUND");
            out.put("products", List.of());
            return out;
        }
        Candidate top = found.get(0);
        boolean clear = top.score() >= 60 && (found.size() == 1 || found.get(1).score() <= top.score() - 10);
        out.put("match", clear ? "ONE_CLEAR_MATCH" : "SEVERAL_POSSIBLE_MATCHES");
        out.put("products", found.stream().map(c -> candidate(c.product(), session)).toList());
        if (clear) remember(session, top.product());
        return out;
    }

    Map<String, Object> getProduct(Integer productId, Session session) {
        ProductInfo p = product(productId);
        remember(session, p);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productId", p.productId());
        m.put("name", p.name());
        put(m, "code", p.code());
        put(m, "productType", p.type());
        put(m, "fabricType", p.fabricType());
        put(m, "material", p.material());
        put(m, "description", p.description());
        m.put("unit", p.unit());
        put(m, "hsnCode", p.hsnCode());
        m.put("gstPct", WholesalePriceLookupService.plain(p.gstPct()));
        if (!p.colors().isEmpty()) m.put("availableColors", p.colors());
        if (!p.designs().isEmpty()) m.put("availableDesigns", p.designs());
        if (!p.specifications().isEmpty()) m.put("specifications", p.specifications());
        List<String> missing = new ArrayList<>();
        if (p.colors().isEmpty()) missing.add("colours");
        if (p.designs().isEmpty()) missing.add("designs");
        if (p.specifications().isEmpty()) missing.add("specifications");
        if (p.hsnCode() == null) missing.add("HSN");
        if (!missing.isEmpty()) m.put("notAvailable", missing);
        return m;
    }

    Map<String, Object> getPrice(Integer productId, BigDecimal quantity, String unit, Session session) {
        ProductInfo p = product(productId);
        remember(session, p);
        if (quantity != null) {
            session.context.put("quantity", WholesalePriceLookupService.plain(quantity));
            session.context.put("quantityUnit", unit != null ? unit : p.unit());
        }
        PriceInfo price = priceLookup.lookup(p.productId(), p.unit(), quantity, unit);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productId", p.productId());
        m.put("productName", p.name());
        m.putAll(json.convertValue(price, new TypeReference<Map<String, Object>>() {}));
        m.put("pricesExcludeGst", true);
        if (price.slabs().isEmpty()) m.put("note", "No wholesale price is configured; the team must share the price.");
        return m;
    }

    Map<String, Object> getStock(Integer productId, BigDecimal quantity, Session session) {
        ProductInfo p = product(productId);
        remember(session, p);
        StockInfo stock = stockLookup.lookup(p.productId(), quantity, Boolean.TRUE.equals(session.settings.getShareStockQuantity()));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productId", p.productId());
        m.put("productName", p.name());
        m.put("inStock", stock.inStock());
        if (stock.availableQuantity() != null) m.put("availableQuantity", stock.availableQuantity());
        else m.put("exactQuantity", "not shared with customers");
        if (quantity != null) {
            m.put("requestedQuantity", WholesalePriceLookupService.plain(quantity));
            m.put("enoughForRequested", stock.enoughForRequested());
        }
        m.put("unit", p.unit());
        return m;
    }

    Map<String, Object> getMinimumQuantity(Integer productId, Session session) {
        ProductInfo p = product(productId);
        remember(session, p);
        PriceInfo price = priceLookup.lookup(p.productId(), p.unit(), null, null);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productId", p.productId());
        m.put("productName", p.name());
        if (price.minimumOrderQuantity() != null) m.put("minimumOrderQuantity", price.minimumOrderQuantity());
        else m.put("minimumOrderQuantity", "not configured");
        m.put("unit", p.unit());
        return m;
    }

    Map<String, Object> getBusinessProfile(Session session) {
        Map<String, Object> m = new LinkedHashMap<>();
        WholesaleBusinessProfile p = profileRepository.findById(WholesaleBusinessProfile.SINGLETON_ID).orElse(null);
        if (p != null) {
            put(m, "businessName", p.getFirmName());
            put(m, "address", p.getAddress());
            put(m, "city", p.getCity());
            put(m, "state", p.getStateName());
            put(m, "pinCode", p.getPinCode());
            put(m, "phone", p.getPhone());
            put(m, "email", p.getEmail());
            put(m, "website", p.getWebsite());
            put(m, "gstin", p.getGstNumber());
        }
        WholesaleAssistantSettings s = session.settings;
        put(m, "businessHours", s.getBusinessHours());
        put(m, "orderingProcess", s.getOrderingProcess());
        put(m, "shippingInformation", s.getShippingInfo());
        put(m, "paymentTerms", s.getPaymentTerms());
        put(m, "otherInformation", s.getAdditionalInfo());
        List<String> missing = new ArrayList<>();
        for (String k : List.of("businessHours", "orderingProcess", "shippingInformation", "paymentTerms", "phone", "address")) {
            if (!m.containsKey(k)) missing.add(k);
        }
        if (!missing.isEmpty()) m.put("notAvailable", missing);
        return m;
    }

    Map<String, Object> getCustomerDetails(Session session) {
        if (session.customerId == null) return Map.of("linked", false);
        WholesaleCustomer c = customerRepository.findById(session.customerId).orElse(null);
        if (c == null) return Map.of("linked", false);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("linked", true);
        m.put("customerName", c.getCustomerName());
        put(m, "businessName", c.getBusinessName());
        put(m, "city", c.getCity());
        put(m, "state", c.getStateName());
        put(m, "gstin", c.getGstNumber());
        return m;
    }

    Map<String, Object> createQuotationDraft(JsonNode input, Session session) {
        WholesaleAssistantSettings s = session.settings;
        Map<String, Object> m = new LinkedHashMap<>();
        List<WholesaleQuotationItemRequest> items = new ArrayList<>();
        List<String> described = new ArrayList<>();
        for (JsonNode it : input.path("items")) {
            ProductInfo p = product(intArg(it, "productId"));
            BigDecimal qty = decimalArg(it, "quantity");
            if (qty == null || qty.signum() <= 0) throw new IllegalArgumentException("Quantity must be greater than 0 for " + p.name());
            if (qty.scale() > 3) qty = qty.setScale(3, java.math.RoundingMode.HALF_UP);
            items.add(new WholesaleQuotationItemRequest(p.productId(), qty, null, null, null, null));
            described.add(p.name() + " " + WholesalePriceLookupService.plain(qty).toPlainString() + " " + p.unit());
        }
        if (items.isEmpty()) throw new IllegalArgumentException("Add at least one product and quantity");
        String summary = String.join(", ", described);
        String notes = textArg(input, "notes");

        if (!Boolean.TRUE.equals(s.getAllowQuotationDrafts())) {
            session.attention.add("Quotation requested: " + summary);
            m.put("created", false);
            m.put("reason", "The team prepares quotations personally; tell the customer the team will send it.");
            return m;
        }
        if (session.customerId == null) {
            session.attention.add("Quotation requested (link a wholesale customer to prepare it): " + summary
                + (notes != null ? " — " + notes : ""));
            m.put("created", false);
            m.put("reason", "Customer is not registered yet; the team will prepare and send the quotation.");
            return m;
        }

        String key = itemsKey(items);
        if (key.equals(session.context.get("quotationKey")) && session.context.get("quotationAt") instanceof String at
                && LocalDateTime.parse(at).isAfter(LocalDateTime.now().minusHours(2))) {
            m.put("created", false);
            m.put("alreadyCreated", true);
            m.put("quotation", session.context.get("quotationRef"));
            return m;
        }

        WholesaleQuotationRequest request = new WholesaleQuotationRequest(session.customerId,
            WholesaleDocumentNumberService.today(), null, null, null, null,
            "Requested via " + session.actor + (notes != null ? ": " + notes : ""), null, items);
        try {
            WholesaleQuotationResponse q;
            if (session.dryRun) {
                q = quotationService.preview(request);
            } else {
                q = quotationService.create(request, session.actor);
                if (Boolean.TRUE.equals(s.getAutoIssueQuotations())) q = quotationService.issue(q.quotationId(), session.actor);
            }
            m.put("created", !session.dryRun);
            if (session.dryRun) m.put("dryRun", "test mode — nothing was saved");
            m.put("status", q.status());
            m.put("approval", "ISSUED".equals(q.status()) ? "issued" : "draft — our team will review and send the final quotation");
            if (q.quotationNumber() != null) m.put("quotationNumber", q.quotationNumber());
            m.put("validUntil", String.valueOf(q.validUntil()));
            m.put("items", q.items().stream().map(i -> {
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("product", i.itemName());
                line.put("quantity", WholesalePriceLookupService.plain(i.quantity()));
                line.put("unit", i.unit());
                line.put("ratePerUnit", i.rate());
                line.put("gstPct", WholesalePriceLookupService.plain(i.gstPct()));
                line.put("amountWithGst", i.totalAmount());
                return line;
            }).toList());
            m.put("grandTotal", q.totals().grandTotal());
            if (q.warnings() != null && !q.warnings().isEmpty()) m.put("warnings", q.warnings());

            if (!session.dryRun) {
                Map<String, Object> ref = new LinkedHashMap<>();
                ref.put("quotationId", q.quotationId());
                ref.put("status", q.status());
                if (q.quotationNumber() != null) ref.put("quotationNumber", q.quotationNumber());
                session.quotation = ref;
                session.context.put("quotationId", q.quotationId());
                session.context.put("quotationRef", ref);
                session.context.put("quotationKey", key);
                session.context.put("quotationAt", LocalDateTime.now().toString());
                session.attention.add(("ISSUED".equals(q.status()) ? "AI issued quotation " + q.quotationNumber()
                    : "AI saved quotation draft #" + q.quotationId() + " — review and issue") + ": " + summary);
            }
        } catch (WholesaleException e) {
            session.attention.add("Quotation requested but could not be prepared (" + e.getMessage() + "): " + summary);
            m.put("created", false);
            m.put("reason", e.getMessage());
        }
        return m;
    }

    // ── Helpers ────────────────────────────────────────────────────────────────────────────────────

    private ProductInfo product(Integer productId) {
        if (productId == null) throw new IllegalArgumentException("productId is required — search for the product first");
        return productLookup.get(productId)
            .orElseThrow(() -> new IllegalArgumentException("No active wholesale product with id " + productId));
    }

    private Map<String, Object> candidate(ProductInfo p, Session session) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productId", p.productId());
        m.put("name", p.name());
        put(m, "productType", p.type());
        put(m, "fabricType", p.fabricType());
        m.put("unit", p.unit());
        m.put("inStock", stockLookup.lookup(p.productId(), null, false).inStock());
        return m;
    }

    /** The conversation is now about this product (so "100 meters?" / "price?" need no product name). */
    private static void remember(Session session, ProductInfo p) {
        if (!Objects.equals(session.context.get("productId"), p.productId())) {
            session.context.remove("quantity");
            session.context.remove("quantityUnit");
        }
        session.context.put("productId", p.productId());
        session.context.put("productName", p.name());
        session.context.put("unit", p.unit());
    }

    private static String itemsKey(List<WholesaleQuotationItemRequest> items) {
        return items.stream().map(i -> i.wholesaleProductId() + "x" + i.quantity().stripTrailingZeros().toPlainString())
            .sorted().reduce((a, b) -> a + "," + b).orElse("");
    }

    private static WholesaleLlmClient.Tool tool(String name, String description, String schema) {
        try {
            return new WholesaleLlmClient.Tool(name, description, new ObjectMapper().readTree(schema));
        } catch (Exception e) {
            throw new IllegalStateException("Bad schema for tool " + name, e);
        }
    }

    private static void put(Map<String, Object> m, String key, Object value) {
        if (value instanceof String s && s.isBlank()) return;
        if (value != null) m.put(key, value);
    }

    private static Integer intArg(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (v.isIntegralNumber()) return v.intValue();
        if (v.isTextual() && v.asText().matches("\\d+")) return Integer.valueOf(v.asText());
        return null;
    }

    private static BigDecimal decimalArg(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (v.isNumber()) return v.decimalValue();
        if (v.isTextual()) {
            try { return new BigDecimal(v.asText().replace(",", "").trim()); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    private static String textArg(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isTextual() && !v.asText().isBlank() ? v.asText().trim() : null;
    }
}

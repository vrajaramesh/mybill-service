package com.example.mybill.wholesale.service;

import com.example.mybill.wholesale.entity.WholesaleCustomer;
import com.example.mybill.wholesale.entity.WholesaleProduct;
import com.example.mybill.wholesale.entity.WholesaleProductImage;
import com.example.mybill.wholesale.repository.WholesaleCustomerRepository;
import com.example.mybill.wholesale.repository.WholesaleProductRepository;
import com.example.mybill.wholesale.service.WholesaleProductLookupService.ProductInfo;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * The wholesale assistant's tools for the WhatsApp AI agent (srisa-agent), so a wholesale chat on the shop's WhatsApp
 * number answers from the same wholesale products, price slabs, stock, business profile and quotations as the
 * in-app wholesale assistant. The agent sends one tool call at a time; the conversation context (current product,
 * last quotation) travels back and forth with each call.
 */
@Service
public class WholesaleAgentGateway {

    /** Tools the agent may call (send_reply is the in-app assistant's own reply tool). */
    public static final Set<String> TOOLS = Set.of("search_wholesale_products", "get_wholesale_product",
        "get_wholesale_price", "get_wholesale_stock", "get_wholesale_minimum_quantity",
        "get_wholesale_business_profile", "get_customer_details", "create_quotation_draft");

    @Autowired private WholesaleAssistantTools tools;
    @Autowired private WholesaleAssistantSettingsService settingsService;
    @Autowired private WholesaleCustomerRepository customerRepository;
    @Autowired private WholesaleProductRepository productRepository;
    @Autowired private WholesaleProductLookupService productLookup;

    /** {result, context, attention}. attention = things the team must follow up (e.g. a quotation to prepare). */
    public Map<String, Object> run(String name, JsonNode input, String phone, Map<String, Object> context, String actor) {
        if (!TOOLS.contains(name)) throw new IllegalArgumentException("Unknown wholesale tool " + name);
        WholesaleAssistantTools.Session session = new WholesaleAssistantTools.Session(
            customerId(phone), false, actor, settingsService.current(), context);
        Map<String, Object> result = tools.execute(name, input, session);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("result", result);
        out.put("context", session.context);
        out.put("attention", session.attention);
        return out;
    }

    /** The registered wholesale customer with this phone (last 10 digits), if exactly one. */
    Integer customerId(String phone) {
        String digits = phone == null ? "" : phone.replaceAll("\\D", "");
        if (digits.length() < 10) return null;
        List<WholesaleCustomer> found = customerRepository.findByPhoneEndingWith(digits.substring(digits.length() - 10));
        return found.size() == 1 ? found.get(0).getWholesaleCustomerId() : null;
    }

    /** A product by its code (SLW-1 on a post = product code "1") with its photos, or empty. */
    @Transactional(readOnly = true)
    public Optional<Map<String, Object>> productByCode(String code) {
        String wanted = code == null ? "" : code.trim();
        for (ProductInfo p : productLookup.activeProducts()) {
            if (p.code() != null && p.code().equalsIgnoreCase(wanted)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("productId", p.productId());
                m.put("code", p.code());
                m.put("name", p.name());
                m.put("unit", p.unit());
                m.put("images", images(p.productId()));
                return Optional.of(m);
            }
        }
        return Optional.empty();
    }

    /** Photo URLs of a product, in display order. */
    @Transactional(readOnly = true)
    public List<String> images(Integer productId) {
        WholesaleProduct product = productRepository.findById(productId).orElse(null);
        if (product == null || !Boolean.TRUE.equals(product.getIsActive())) return List.of();
        return product.getImages().stream()
            .sorted(Comparator.comparing(i -> i.getSortOrder() == null ? 0 : i.getSortOrder()))
            .map(WholesaleProductImage::getImageUrl).filter(Objects::nonNull).toList();
    }
}

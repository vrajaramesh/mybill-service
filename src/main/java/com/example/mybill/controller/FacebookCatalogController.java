package com.example.mybill.controller;

import com.example.mybill.dto.Firm;
import com.example.mybill.multitenancy.JwtUtil;
import com.example.mybill.multitenancy.TenantContext;
import com.example.mybill.repository.FirmRepository;
import com.example.mybill.service.ProductImageRepository;
import com.example.mybill.service.ProductService;
import com.example.mybill.service.Product;
import com.example.mybill.service.ProductImage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.Optional;

@RestController
@RequestMapping("/api/facebook/catalog")
public class FacebookCatalogController {

    @Value("${meta.catalog.id}") private String catalogId;
    @Value("${meta.access.token:}") private String accessToken;
    @Value("${meta.ecom.base.url}") private String ecomBaseUrl;

    @Autowired private ProductService productService;
    @Autowired private ProductImageRepository productImageRepository;
    @Autowired private FirmRepository firmRepository;
    @Autowired private JwtUtil jwtUtil;

    private static final Logger log = Logger.getLogger(FacebookCatalogController.class.getName());
    private final ObjectMapper mapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();

    @PostMapping("/publish/{productId}")
    public ResponseEntity<?> publishProduct(@PathVariable Integer productId,
                                             HttpServletRequest req) {
        if (accessToken == null || accessToken.isBlank())
            return ResponseEntity.status(503).body(Map.of("error", "META_ACCESS_TOKEN not configured"));

        Optional<Product> productOpt = productService.getProductById(productId);
        if (productOpt.isEmpty())
            return ResponseEntity.status(404).body(Map.of("error", "Product not found"));

        Product product = productOpt.get();

        // Get firmCode from current tenant schema
        String schema = TenantContext.getCurrentTenant();
        String firmCode = firmRepository.findBySchemaName(schema)
            .map(Firm::getFirmCode)
            .orElse(schema.replace("firm_", ""));

        // Collect images (exclude videos)
        List<ProductImage> images = productImageRepository
            .findByProductProductIdOrderBySortOrderAscCreatedAtAsc(productId)
            .stream()
            .filter(i -> !"video".equals(i.getMediaType()))
            .toList();

        if (images.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "Product has no images. Add at least one image before publishing."));

        // Build product URL
        String productUrl = ecomBaseUrl + "?firmCode=" + firmCode + "&product=" + productId;

        // Build Meta items_batch payload
        ObjectNode data = mapper.createObjectNode();
        data.put("name", product.getProductName());
        data.put("description", product.getDescription() != null && !product.getDescription().isBlank()
            ? product.getDescription() : product.getProductName());

        // Price as "AMOUNT CURRENCY" string (Meta catalog format)
        BigDecimal price = product.getSellingPrice() != null ? product.getSellingPrice() : BigDecimal.ZERO;
        data.put("price", price.setScale(2, RoundingMode.HALF_UP).toPlainString() + " INR");

        data.put("availability", product.getStockQuantity() != null
            && product.getStockQuantity().compareTo(BigDecimal.ZERO) > 0 ? "in stock" : "out of stock");
        data.put("condition", "new");
        data.put("url", productUrl);
        data.put("image_url", images.get(0).getImageUrl());

        if (images.size() > 1) {
            ArrayNode extras = mapper.createArrayNode();
            for (int i = 1; i < images.size(); i++) extras.add(images.get(i).getImageUrl());
            data.set("additional_image_urls", extras);
        }

        // google_product_category: 2271 = Apparel & Accessories (Google numeric taxonomy ID)
        data.put("google_product_category", 2271);

        ObjectNode request = mapper.createObjectNode();
        request.put("method", "UPSERT");
        request.put("retailer_id", "MYBILL_" + productId);
        request.set("data", data);

        ArrayNode requests = mapper.createArrayNode();
        requests.add(request);

        ObjectNode payload = mapper.createObjectNode();
        payload.put("allow_upsert", true);
        payload.put("item_type", "PRODUCT_ITEM");
        payload.set("requests", requests);

        // Call Meta Graph API
        String url = "https://graph.facebook.com/v21.0/" + catalogId + "/items_batch";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);

        log.info("[FB-CATALOG] Sending payload: " + payload.toString());
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                url, new HttpEntity<>(payload.toString(), headers), String.class);
            log.info("[FB-CATALOG] Meta response: " + response.getBody());
            return ResponseEntity.ok(Map.of(
                "message", "Published to Facebook catalog",
                "productId", productId,
                "productName", product.getProductName(),
                "imagesCount", images.size(),
                "meta", response.getBody()
            ));
        } catch (HttpClientErrorException e) {
            return ResponseEntity.status(502).body(Map.of(
                "error", "Meta API rejected request: " + e.getResponseBodyAsString()
            ));
        } catch (Exception e) {
            return ResponseEntity.status(502).body(Map.of(
                "error", "Meta API error: " + e.getMessage()
            ));
        }
    }
}

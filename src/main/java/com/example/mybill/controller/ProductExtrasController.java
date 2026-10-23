package com.example.mybill.controller;

import com.example.mybill.boutique.config.BoutiqueSchemaInitializer;
import com.example.mybill.multitenancy.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;

/**
 * Extra product fields used by the WhatsApp assistant, kept beside the products row (db/boutique/V13):
 * - ai_instructions: what the assistant should tell / ask about this product ("min 1 m; suggest 2.5 m for a kurti");
 * - size stock (readymade garments): each product has its own sizes with stock per size. Saving them also sets the
 *   product's stock_quantity (sum) and sizes (labels), so billing and the website keep working as before.
 */
@RestController
@RequestMapping("/api/products/{productId}/extras")
public class ProductExtrasController {

    static final int MAX_SIZES = 30;

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private BoutiqueSchemaInitializer boutiqueSchemaInitializer;

    @GetMapping
    public ResponseEntity<?> get(@PathVariable Integer productId) {
        String s = TenantContext.getCurrentTenant();
        if (s == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        boutiqueSchemaInitializer.ensureSchema(s);
        List<String> instructions = jdbcTemplate.queryForList(
            "SELECT ai_instructions FROM \"" + s + "\".products WHERE product_id = ?", String.class, productId);
        if (instructions.isEmpty()) return ResponseEntity.notFound().build();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("aiInstructions", instructions.get(0));
        out.put("sizeStock", sizeStock(s, productId));
        return ResponseEntity.ok(out);
    }

    /** Body {aiInstructions?, sizeStock?: [{size, stock}]} - a key left out is not changed; sizeStock [] clears sizes. */
    @PutMapping
    @Transactional
    public ResponseEntity<?> save(@PathVariable Integer productId, @RequestBody Map<String, Object> body) {
        String s = TenantContext.getCurrentTenant();
        if (s == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        boutiqueSchemaInitializer.ensureSchema(s);
        Integer exists = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM \"" + s + "\".products WHERE product_id = ?", Integer.class, productId);
        if (exists == null || exists == 0) return ResponseEntity.notFound().build();

        if (body.containsKey("aiInstructions")) {
            String text = body.get("aiInstructions") instanceof String t && !t.isBlank() ? t.trim() : null;
            if (text != null && text.length() > 2000) {
                return ResponseEntity.badRequest().body(Map.of("error", "AI instructions must be at most 2000 characters"));
            }
            jdbcTemplate.update("UPDATE \"" + s + "\".products SET ai_instructions = ? WHERE product_id = ?", text, productId);
        }

        if (body.get("sizeStock") instanceof List<?> rows) {
            List<String> labels = new ArrayList<>();
            List<BigDecimal> stocks = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (Object row : rows) {
                if (!(row instanceof Map<?, ?> m)) continue;
                String size = String.valueOf(m.get("size") == null ? "" : m.get("size")).trim();
                if (size.isEmpty()) continue;
                if (size.length() > 30) return ResponseEntity.badRequest().body(Map.of("error", "Size '" + size + "' is too long"));
                if (!seen.add(size.toUpperCase(Locale.ROOT))) {
                    return ResponseEntity.badRequest().body(Map.of("error", "Size '" + size + "' is listed twice"));
                }
                BigDecimal stock;
                try {
                    stock = new BigDecimal(String.valueOf(m.get("stock") == null ? "0" : m.get("stock")).trim());
                } catch (NumberFormatException e) {
                    return ResponseEntity.badRequest().body(Map.of("error", "Stock for size " + size + " must be a number"));
                }
                if (stock.signum() < 0) return ResponseEntity.badRequest().body(Map.of("error", "Stock can't be negative"));
                labels.add(size);
                stocks.add(stock);
            }
            if (labels.size() > MAX_SIZES) return ResponseEntity.badRequest().body(Map.of("error", "At most " + MAX_SIZES + " sizes"));
            jdbcTemplate.update("DELETE FROM \"" + s + "\".product_size_stock WHERE product_id = ?", productId);
            for (int i = 0; i < labels.size(); i++) {
                jdbcTemplate.update("INSERT INTO \"" + s + "\".product_size_stock (product_id, size_label, stock, sort_order) VALUES (?, ?, ?, ?)",
                    productId, labels.get(i), stocks.get(i), i);
            }
            if (!labels.isEmpty()) {
                BigDecimal total = stocks.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
                jdbcTemplate.update("UPDATE \"" + s + "\".products SET stock_quantity = ?, sizes = ?, updated_at = NOW() WHERE product_id = ?",
                    total, String.join(",", labels), productId);
            }
        }
        return get(productId);
    }

    private List<Map<String, Object>> sizeStock(String s, int productId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : jdbcTemplate.queryForList(
                "SELECT size_label, stock FROM \"" + s + "\".product_size_stock WHERE product_id = ? ORDER BY sort_order, size_label",
                productId)) {
            out.add(Map.of("size", r.get("size_label"), "stock", r.get("stock")));
        }
        return out;
    }
}

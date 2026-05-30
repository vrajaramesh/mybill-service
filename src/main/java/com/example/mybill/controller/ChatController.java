package com.example.mybill.controller;

import com.example.mybill.dto.Firm;
import com.example.mybill.multitenancy.TenantContext;
import com.example.mybill.repository.FirmRepository;
import com.example.mybill.service.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

@RestController
@RequestMapping("/api/public")
public class ChatController {

    @Autowired private FirmRepository firmRepository;
    @Autowired private ChatService chatService;
    @Autowired private ProductVectorService productVectorService;
    @Autowired private DataSource dataSource;

    @PostMapping("/{firmCode}/chat")
    public ResponseEntity<?> chat(
            @PathVariable String firmCode,
            @RequestBody ChatService.ChatRequest request) {

        Optional<Firm> firmOpt = firmRepository.findByFirmCode(firmCode.toLowerCase().trim());
        if (firmOpt.isEmpty() || !Boolean.TRUE.equals(firmOpt.get().getIsActive()))
            return ResponseEntity.notFound().build();

        Firm firm = firmOpt.get();
        ChatService.ChatResponse response = chatService.chat(
            firm.getSchemaName(),
            firm.getFirmName(),
            request.sessionId,
            request.message,
            request.ecomUrl,
            request.imageBase64,
            request.imageMimeType);

        return ResponseEntity.ok(response);
    }

    /** Returns embedding counts so you can verify reembed-all worked. */
    @GetMapping("/{firmCode}/embedding-status")
    public ResponseEntity<?> embeddingStatus(@PathVariable String firmCode) {
        Optional<Firm> firmOpt = firmRepository.findByFirmCode(firmCode.toLowerCase().trim());
        if (firmOpt.isEmpty()) return ResponseEntity.notFound().build();
        String schema = firmOpt.get().getSchemaName();
        try (Connection c = dataSource.getConnection()) {
            c.createStatement().execute("SET search_path TO \"" + schema + "\", public");
            ResultSet rs = c.createStatement().executeQuery(
                "SELECT " +
                "  (SELECT COUNT(*) FROM products WHERE is_active = TRUE) AS total_products," +
                "  (SELECT COUNT(*) FROM product_embeddings) AS embedded_products," +
                "  (SELECT COUNT(*) FROM products p JOIN product_embeddings pe ON pe.product_id = p.product_id" +
                "   WHERE p.is_active = TRUE AND p.stock_quantity > 0) AS embedded_in_stock"
            );
            if (rs.next()) {
                return ResponseEntity.ok(Map.of(
                    "totalProducts",      rs.getInt("total_products"),
                    "embeddedProducts",   rs.getInt("embedded_products"),
                    "embeddedInStock",    rs.getInt("embedded_in_stock")
                ));
            }
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
        return ResponseEntity.ok(Map.of());
    }

    /** Trigger FashionCLIP re-embedding for every active product via JDBC (schema-safe). */
    @PostMapping("/{firmCode}/reembed-all")
    public ResponseEntity<?> reembedAll(@PathVariable String firmCode) {
        Optional<Firm> firmOpt = firmRepository.findByFirmCode(firmCode.toLowerCase().trim());
        if (firmOpt.isEmpty() || !Boolean.TRUE.equals(firmOpt.get().getIsActive()))
            return ResponseEntity.notFound().build();

        String schema = firmOpt.get().getSchemaName();

        // Use JDBC directly — avoids JPA multi-tenancy ambiguity
        String sql =
            "SELECT p.product_id, " +
            "  (SELECT i.image_url FROM product_images i" +
            "   WHERE i.product_id = p.product_id ORDER BY i.created_at ASC LIMIT 1) AS image_url" +
            " FROM products p WHERE p.is_active = TRUE";

        int triggered = 0, total = 0;
        try (Connection c = dataSource.getConnection()) {
            c.createStatement().execute("SET search_path TO \"" + schema + "\", public");
            try (ResultSet rs = c.createStatement().executeQuery(sql)) {
                while (rs.next()) {
                    total++;
                    int productId = rs.getInt("product_id");
                    String imageUrl = rs.getString("image_url");
                    try {
                        TenantContext.setCurrentTenant(schema);
                        productVectorService.triggerEmbeddingAsync(productId, imageUrl);
                        triggered++;
                    } catch (Exception e) {
                        System.err.println("[REEMBED] Skipped product " + productId + ": " + e.getMessage());
                    } finally {
                        TenantContext.clear();
                    }
                }
            }
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }

        System.err.println("[REEMBED] Triggered " + triggered + "/" + total + " products for schema " + schema);
        return ResponseEntity.ok(Map.of("triggered", triggered, "total", total, "schema", schema));
    }
}

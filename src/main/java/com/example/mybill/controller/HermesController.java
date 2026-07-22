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
@RequestMapping("/api/hermes")
public class HermesController {

    @Autowired private FirmRepository firmRepository;
    @Autowired private HermesAgentService hermesAgent;
    @Autowired private ProductMarketingContentRepository contentRepo;
    @Autowired private DataSource dataSource;

    // ── Marketing content for a single product ────────────────────────────────

    @GetMapping("/{firmCode}/content/{productId}")
    public ResponseEntity<?> getContent(@PathVariable String firmCode,
                                        @PathVariable Integer productId) {
        return withFirm(firmCode, firm -> {
            TenantContext.setCurrentTenant(firm.getSchemaName());
            try {
                return contentRepo.findById(productId)
                    .<ResponseEntity<?>>map(ResponseEntity::ok)
                    .orElse(ResponseEntity.notFound().build());
            } finally {
                TenantContext.clear();
            }
        });
    }

    @PostMapping("/{firmCode}/content/{productId}/regenerate")
    public ResponseEntity<?> regenerate(@PathVariable String firmCode,
                                        @PathVariable Integer productId) {
        return withFirm(firmCode, firm -> {
            hermesAgent.generateContentAsync(productId, firm.getSchemaName(), firm.getFirmName());
            return ResponseEntity.ok(Map.of("status", "queued", "productId", productId));
        });
    }

    // ── All product content for this firm (paginated) ─────────────────────────

    @GetMapping("/{firmCode}/content")
    public ResponseEntity<?> listContent(@PathVariable String firmCode,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        return withFirm(firmCode, firm -> {
            String schema = firm.getSchemaName();
            List<Map<String, Object>> rows = new ArrayList<>();
            String sql =
                "SELECT p.product_id, p.product_name, p.selling_price, p.category, " +
                "  (SELECT image_url FROM product_images i WHERE i.product_id=p.product_id " +
                "   ORDER BY i.created_at ASC LIMIT 1) AS image_url, " +
                "  mc.instagram_caption, mc.whatsapp_text, mc.hashtags, mc.seo_description, mc.generated_at " +
                "FROM products p " +
                "LEFT JOIN product_marketing_content mc ON mc.product_id = p.product_id " +
                "WHERE p.is_active = TRUE " +
                "ORDER BY p.created_at DESC " +
                "LIMIT ? OFFSET ?";
            try (Connection c = dataSource.getConnection()) {
                c.createStatement().execute("SET search_path TO \"" + schema + "\", public");
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setInt(1, size);
                    ps.setInt(2, page * size);
                    ResultSet rs = ps.executeQuery();
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("productId",        rs.getInt("product_id"));
                        row.put("productName",       rs.getString("product_name"));
                        row.put("sellingPrice",      rs.getBigDecimal("selling_price"));
                        row.put("category",          rs.getString("category"));
                        row.put("imageUrl",          rs.getString("image_url"));
                        row.put("instagramCaption",  rs.getString("instagram_caption"));
                        row.put("whatsappText",      rs.getString("whatsapp_text"));
                        row.put("hashtags",          rs.getString("hashtags"));
                        row.put("seoDescription",    rs.getString("seo_description"));
                        row.put("generatedAt",       rs.getString("generated_at"));
                        rows.add(row);
                    }
                }
                return ResponseEntity.ok(rows);
            } catch (Exception e) {
                return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
            }
        });
    }

    // ── Campaigns ─────────────────────────────────────────────────────────────

    @GetMapping("/{firmCode}/campaigns")
    public ResponseEntity<?> listCampaigns(@PathVariable String firmCode) {
        return withFirm(firmCode, firm -> {
            String schema = firm.getSchemaName();
            List<Map<String, Object>> rows = new ArrayList<>();
            String sql = "SELECT id, campaign_type, caption, image_url, channel, status, " +
                         "sent_count, failed_count, scheduled_at, executed_at, created_at " +
                         "FROM hermes_campaigns ORDER BY created_at DESC LIMIT 50";
            try (Connection c = dataSource.getConnection()) {
                c.createStatement().execute("SET search_path TO \"" + schema + "\", public");
                ResultSet rs = c.createStatement().executeQuery(sql);
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id",            rs.getLong("id"));
                    row.put("campaignType",  rs.getString("campaign_type"));
                    row.put("caption",       rs.getString("caption"));
                    row.put("imageUrl",      rs.getString("image_url"));
                    row.put("channel",       rs.getString("channel"));
                    row.put("status",        rs.getString("status"));
                    row.put("sentCount",     rs.getInt("sent_count"));
                    row.put("failedCount",   rs.getInt("failed_count"));
                    row.put("scheduledAt",   rs.getString("scheduled_at"));
                    row.put("executedAt",    rs.getString("executed_at"));
                    row.put("createdAt",     rs.getString("created_at"));
                    rows.add(row);
                }
                return ResponseEntity.ok(rows);
            } catch (Exception e) {
                return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
            }
        });
    }

    /** Manual broadcast — send with custom caption + image immediately. */
    @PostMapping("/{firmCode}/broadcast")
    public ResponseEntity<?> broadcast(@PathVariable String firmCode,
                                       @RequestBody BroadcastRequest req) {
        return withFirm(firmCode, firm -> {
            Map<String, Object> result = hermesAgent.sendManualCampaign(
                firm.getSchemaName(), firmCode, firm.getFirmName(),
                req.productIds != null ? req.productIds : List.of(),
                req.caption, req.imageUrl);
            return ResponseEntity.ok(result);
        });
    }

    // ── Contacts ──────────────────────────────────────────────────────────────

    @GetMapping("/{firmCode}/contacts")
    public ResponseEntity<?> listContacts(@PathVariable String firmCode) {
        return withFirm(firmCode, firm -> {
            String schema = firm.getSchemaName();
            List<Map<String, Object>> rows = new ArrayList<>();
            String sql = "SELECT id, phone, customer_name, opted_in, added_by, last_messaged, created_at " +
                         "FROM hermes_contacts ORDER BY created_at DESC";
            try (Connection c = dataSource.getConnection()) {
                c.createStatement().execute("SET search_path TO \"" + schema + "\", public");
                ResultSet rs = c.createStatement().executeQuery(sql);
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id",           rs.getLong("id"));
                    row.put("phone",        rs.getString("phone"));
                    row.put("customerName", rs.getString("customer_name"));
                    row.put("optedIn",      rs.getBoolean("opted_in"));
                    row.put("addedBy",      rs.getString("added_by"));
                    row.put("lastMessaged", rs.getString("last_messaged"));
                    row.put("createdAt",    rs.getString("created_at"));
                    rows.add(row);
                }
                return ResponseEntity.ok(rows);
            } catch (Exception e) {
                return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
            }
        });
    }

    @PostMapping("/{firmCode}/contacts")
    public ResponseEntity<?> addContact(@PathVariable String firmCode,
                                        @RequestBody ContactRequest req) {
        return withFirm(firmCode, firm -> {
            String schema = firm.getSchemaName();
            String sql = "INSERT INTO hermes_contacts (phone, customer_name, opted_in, added_by) " +
                         "VALUES (?, ?, TRUE, 'manual') " +
                         "ON CONFLICT (phone) DO UPDATE SET customer_name = EXCLUDED.customer_name, opted_in = TRUE";
            try (Connection c = dataSource.getConnection()) {
                c.createStatement().execute("SET search_path TO \"" + schema + "\", public");
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setString(1, req.phone.trim());
                    ps.setString(2, req.customerName != null ? req.customerName.trim() : null);
                    ps.executeUpdate();
                }
                return ResponseEntity.ok(Map.of("status", "added", "phone", req.phone));
            } catch (Exception e) {
                return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
            }
        });
    }

    @DeleteMapping("/{firmCode}/contacts/{id}")
    public ResponseEntity<?> removeContact(@PathVariable String firmCode, @PathVariable Long id) {
        return withFirm(firmCode, firm -> {
            String schema = firm.getSchemaName();
            String sql = "UPDATE hermes_contacts SET opted_in = FALSE WHERE id = ?";
            try (Connection c = dataSource.getConnection()) {
                c.createStatement().execute("SET search_path TO \"" + schema + "\", public");
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setLong(1, id);
                    ps.executeUpdate();
                }
                return ResponseEntity.ok(Map.of("status", "opted_out"));
            } catch (Exception e) {
                return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
            }
        });
    }

    // ── Stats summary ─────────────────────────────────────────────────────────

    @GetMapping("/{firmCode}/stats")
    public ResponseEntity<?> stats(@PathVariable String firmCode) {
        return withFirm(firmCode, firm -> {
            String schema = firm.getSchemaName();
            try (Connection c = dataSource.getConnection()) {
                c.createStatement().execute("SET search_path TO \"" + schema + "\", public");
                ResultSet rs = c.createStatement().executeQuery(
                    "SELECT " +
                    "  (SELECT COUNT(*) FROM product_marketing_content) AS content_generated, " +
                    "  (SELECT COUNT(*) FROM hermes_campaigns WHERE status='sent') AS campaigns_sent, " +
                    "  (SELECT COALESCE(SUM(sent_count),0) FROM hermes_campaigns WHERE status='sent') AS total_messages_sent, " +
                    "  (SELECT COUNT(*) FROM hermes_contacts WHERE opted_in=TRUE) AS active_contacts"
                );
                if (rs.next()) {
                    return ResponseEntity.ok(Map.of(
                        "contentGenerated",  rs.getLong("content_generated"),
                        "campaignsSent",     rs.getLong("campaigns_sent"),
                        "totalMessagesSent", rs.getLong("total_messages_sent"),
                        "activeContacts",    rs.getLong("active_contacts")
                    ));
                }
                return ResponseEntity.ok(Map.of());
            } catch (Exception e) {
                return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
            }
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    @FunctionalInterface
    interface FirmAction {
        ResponseEntity<?> run(Firm firm) throws Exception;
    }

    private ResponseEntity<?> withFirm(String firmCode, FirmAction action) {
        Optional<Firm> firmOpt = firmRepository.findByFirmCode(firmCode.toLowerCase().trim());
        if (firmOpt.isEmpty() || !Boolean.TRUE.equals(firmOpt.get().getIsActive()))
            return ResponseEntity.notFound().build();
        try {
            return action.run(firmOpt.get());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Request DTOs ──────────────────────────────────────────────────────────

    public static class BroadcastRequest {
        public String caption;
        public String imageUrl;
        public List<Integer> productIds;
    }

    public static class ContactRequest {
        public String phone;
        public String customerName;
    }
}

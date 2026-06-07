package com.example.mybill.service;

import com.example.mybill.multitenancy.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Hermes Marketing Automation — orchestrator.
 *
 * Entry points:
 *  - generateContentAsync()  called after every product save (non-blocking)
 *  - triggerNewArrivalCampaign() called by CampaignSchedulerService on schedule
 *  - triggerDeadStockCampaign()  called by CampaignSchedulerService on schedule
 */
@Service
public class HermesAgentService {

    @Autowired private ProductContentService contentService;
    @Autowired private ProductMarketingContentRepository contentRepo;
    @Autowired private BroadcastTriggerService broadcastService;
    @Autowired private DataSource dataSource;

    private final ObjectMapper json = new ObjectMapper();

    // ── Content generation ────────────────────────────────────────────────────

    /**
     * Async: generate marketing content for a single product.
     * Called immediately after product save — runs in background, never blocks the HTTP response.
     */
    @Async
    public void generateContentAsync(Integer productId, String schema, String firmName) {
        try {
            TenantContext.setCurrentTenant(schema);
            Product product = loadProduct(schema, productId);
            if (product == null) {
                System.err.println("[Hermes] Product " + productId + " not found in schema " + schema);
                return;
            }
            ProductMarketingContent content = contentService.generate(product, firmName);
            contentRepo.save(content);
            System.out.println("[Hermes] Content generated for product " + productId + " (" + schema + ")");
        } catch (Exception e) {
            System.err.println("[Hermes] generateContentAsync failed for product " + productId + ": " + e.getMessage());
        } finally {
            TenantContext.clear();
        }
    }

    // ── Campaign: new arrivals ────────────────────────────────────────────────

    /**
     * Find products added in the last 7 days; build a WhatsApp broadcast campaign.
     * Called every Monday by CampaignSchedulerService.
     */
    public void triggerNewArrivalCampaign(String schema, String firmName, String firmCode) {
        try {
            TenantContext.setCurrentTenant(schema);
            List<Product> newProducts = fetchRecentProducts(schema, 7);
            if (newProducts.isEmpty()) {
                System.out.println("[Hermes] No new products in last 7 days for " + firmCode);
                return;
            }

            // Pick the first product's image as the campaign image
            String imageUrl = fetchFirstImage(schema, newProducts.get(0).getProductId());
            String caption   = buildNewArrivalCaption(newProducts, firmName);
            List<Integer> productIds = newProducts.stream().map(Product::getProductId).toList();

            long campaignId = saveCampaign(schema, "weekly", productIds, caption, imageUrl);
            List<String> phones = fetchOptedInPhones(schema);

            if (phones.isEmpty()) {
                System.out.println("[Hermes] No broadcast contacts for " + firmCode);
                updateCampaignStatus(schema, campaignId, "skipped", 0, 0);
                return;
            }

            BroadcastTriggerService.BroadcastResult result =
                broadcastService.sendBroadcast(firmCode, phones, imageUrl, caption);

            updateCampaignStatus(schema, campaignId, "sent", result.sent(), result.failed());
            System.out.println("[Hermes] Weekly campaign sent for " + firmCode +
                               " — " + result.sent() + " delivered, " + result.failed() + " failed");

        } catch (Exception e) {
            System.err.println("[Hermes] New arrival campaign failed for " + firmCode + ": " + e.getMessage());
        } finally {
            TenantContext.clear();
        }
    }

    // ── Manual campaign trigger (from HermesController) ───────────────────────

    public Map<String, Object> sendManualCampaign(String schema, String firmCode, String firmName,
                                                   List<Integer> productIds, String caption, String imageUrl) {
        try {
            TenantContext.setCurrentTenant(schema);
            long campaignId = saveCampaign(schema, "manual", productIds, caption, imageUrl);
            List<String> phones = fetchOptedInPhones(schema);
            if (phones.isEmpty()) {
                updateCampaignStatus(schema, campaignId, "skipped", 0, 0);
                return Map.of("status", "skipped", "reason", "No opted-in contacts");
            }
            BroadcastTriggerService.BroadcastResult result =
                broadcastService.sendBroadcast(firmCode, phones, imageUrl, caption);
            updateCampaignStatus(schema, campaignId, "sent", result.sent(), result.failed());
            return Map.of("campaignId", campaignId, "sent", result.sent(), "failed", result.failed());
        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        } finally {
            TenantContext.clear();
        }
    }

    // ── Caption builders ──────────────────────────────────────────────────────

    private String buildNewArrivalCaption(List<Product> products, String firmName) {
        StringBuilder sb = new StringBuilder();
        sb.append("New Arrivals at ").append(firmName).append("!\n\n");
        for (int i = 0; i < Math.min(3, products.size()); i++) {
            Product p = products.get(i);
            sb.append("• ").append(p.getProductName());
            if (p.getSellingPrice() != null)
                sb.append(" — ₹").append(p.getSellingPrice()).append("/m");
            sb.append("\n");
        }
        if (products.size() > 3)
            sb.append("and ").append(products.size() - 3).append(" more...\n");
        sb.append("\nReply to enquire or visit our shop.");
        return sb.toString();
    }

    // ── JDBC helpers (schema-safe) ────────────────────────────────────────────

    private Product loadProduct(String schema, Integer productId) {
        String sql = "SELECT product_id, product_name, description, selling_price, tags, suitable_for " +
                     "FROM products WHERE product_id = ?";
        try (Connection c = dataSource.getConnection()) {
            c.createStatement().execute("SET search_path TO \"" + schema + "\", public");
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setInt(1, productId);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) return mapProduct(rs);
            }
        } catch (Exception e) {
            System.err.println("[Hermes] loadProduct error: " + e.getMessage());
        }
        return null;
    }

    private List<Product> fetchRecentProducts(String schema, int days) {
        List<Product> list = new ArrayList<>();
        String sql = "SELECT product_id, product_name, description, selling_price, tags, suitable_for " +
                     "FROM products WHERE is_active = TRUE AND created_at >= NOW() - INTERVAL '" + days + " days' " +
                     "ORDER BY created_at DESC LIMIT 10";
        try (Connection c = dataSource.getConnection()) {
            c.createStatement().execute("SET search_path TO \"" + schema + "\", public");
            ResultSet rs = c.createStatement().executeQuery(sql);
            while (rs.next()) list.add(mapProduct(rs));
        } catch (Exception e) {
            System.err.println("[Hermes] fetchRecentProducts error: " + e.getMessage());
        }
        return list;
    }

    private String fetchFirstImage(String schema, Integer productId) {
        String sql = "SELECT image_url FROM product_images WHERE product_id = ? ORDER BY created_at ASC LIMIT 1";
        try (Connection c = dataSource.getConnection()) {
            c.createStatement().execute("SET search_path TO \"" + schema + "\", public");
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setInt(1, productId);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) return rs.getString(1);
            }
        } catch (Exception e) {
            System.err.println("[Hermes] fetchFirstImage error: " + e.getMessage());
        }
        return null;
    }

    private List<String> fetchOptedInPhones(String schema) {
        List<String> phones = new ArrayList<>();
        String sql = "SELECT phone FROM hermes_contacts WHERE opted_in = TRUE ORDER BY created_at";
        try (Connection c = dataSource.getConnection()) {
            c.createStatement().execute("SET search_path TO \"" + schema + "\", public");
            ResultSet rs = c.createStatement().executeQuery(sql);
            while (rs.next()) phones.add(rs.getString(1));
        } catch (Exception e) {
            System.err.println("[Hermes] fetchOptedInPhones error: " + e.getMessage());
        }
        return phones;
    }

    private long saveCampaign(String schema, String type, List<Integer> productIds,
                               String caption, String imageUrl) throws Exception {
        String sql = "INSERT INTO hermes_campaigns (campaign_type, product_ids, caption, image_url, status, scheduled_at) " +
                     "VALUES (?, ?, ?, ?, 'pending', NOW()) RETURNING id";
        try (Connection c = dataSource.getConnection()) {
            c.createStatement().execute("SET search_path TO \"" + schema + "\", public");
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, type);
                ps.setArray(2, c.createArrayOf("integer",
                    productIds.stream().map(Object.class::cast).toArray()));
                ps.setString(3, caption);
                ps.setString(4, imageUrl);
                ResultSet rs = ps.executeQuery();
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void updateCampaignStatus(String schema, long campaignId, String status, int sent, int failed) {
        String sql = "UPDATE hermes_campaigns SET status=?, sent_count=?, failed_count=?, executed_at=NOW() WHERE id=?";
        try (Connection c = dataSource.getConnection()) {
            c.createStatement().execute("SET search_path TO \"" + schema + "\", public");
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, status);
                ps.setInt(2, sent);
                ps.setInt(3, failed);
                ps.setLong(4, campaignId);
                ps.executeUpdate();
            }
        } catch (Exception e) {
            System.err.println("[Hermes] updateCampaignStatus error: " + e.getMessage());
        }
    }

    private Product mapProduct(ResultSet rs) throws SQLException {
        Product p = new Product();
        p.setProductId(rs.getInt("product_id"));
        p.setProductName(rs.getString("product_name"));
        p.setDescription(rs.getString("description"));
        p.setSellingPrice(rs.getBigDecimal("selling_price"));
        p.setTags(rs.getString("tags"));
        p.setSuitableFor(rs.getString("suitable_for"));
        return p;
    }
}

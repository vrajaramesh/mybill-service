package com.example.mybill.service;

import com.example.mybill.multitenancy.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Hermes Phase 2 — Dead Stock Campaign.
 *
 * Finds products with stock but no sales in the last N days,
 * asks Claude to write an urgency WhatsApp broadcast, and sends it.
 *
 * Called by CampaignSchedulerService every Monday.
 */
@Service
public class DeadStockCampaignService {

    private static final int DEAD_DAYS  = 60;   // days without a sale → "dead stock"
    private static final int MAX_ITEMS  = 6;    // max products to mention in one campaign

    @Autowired private ClaudeService claudeService;
    @Autowired private BroadcastTriggerService broadcastService;
    @Autowired private DataSource dataSource;

    // ── Public entry point ────────────────────────────────────────────────────

    /**
     * Run the full dead-stock campaign for one firm.
     * Sets/clears TenantContext internally — safe to call from scheduler.
     */
    public void run(String schema, String firmName, String firmCode) {
        try {
            TenantContext.setCurrentTenant(schema);

            List<DeadStockItem> items = fetchDeadStock(schema, DEAD_DAYS, MAX_ITEMS);
            if (items.isEmpty()) {
                System.out.println("[DeadStock] No dead stock for " + firmCode);
                return;
            }

            System.out.println("[DeadStock] " + items.size() + " dead-stock products found for " + firmCode);

            String caption  = generateUrgencyCopy(items, firmName);
            String imageUrl = items.get(0).imageUrl;
            List<Integer> ids = items.stream().map(i -> i.productId).toList();

            long campaignId = saveCampaign(schema, ids, caption, imageUrl);

            List<String> phones = fetchOptedInPhones(schema);
            if (phones.isEmpty()) {
                updateCampaignStatus(schema, campaignId, "skipped", 0, 0);
                System.out.println("[DeadStock] No contacts to broadcast for " + firmCode);
                return;
            }

            BroadcastTriggerService.BroadcastResult result =
                broadcastService.sendBroadcast(firmCode, phones, imageUrl, caption);
            updateCampaignStatus(schema, campaignId, "sent", result.sent(), result.failed());

            System.out.println("[DeadStock] Campaign sent for " + firmCode +
                               " — " + result.sent() + " delivered");

        } catch (Exception e) {
            System.err.println("[DeadStock] Campaign failed for " + firmCode + ": " + e.getMessage());
        } finally {
            TenantContext.clear();
        }
    }

    // ── Claude urgency copy ───────────────────────────────────────────────────

    private String generateUrgencyCopy(List<DeadStockItem> items, String firmName) {
        try {
            return claudeService.complete(systemPrompt(), userPrompt(items, firmName));
        } catch (Exception e) {
            System.err.println("[DeadStock] Claude failed, using fallback: " + e.getMessage());
            return fallbackCaption(items, firmName);
        }
    }

    private String systemPrompt() {
        return """
            You are a marketing copywriter for an Indian fabric boutique.
            Write persuasive, warm WhatsApp broadcast messages that create genuine urgency.
            Your tone is friendly — like a trusted shop owner texting a regular customer.
            Keep it under 200 words. Use at most 2 emojis. No generic phrases like "limited time offer".
            Respond with ONLY the WhatsApp message text — no explanation, no quotes around it.
            """;
    }

    private String userPrompt(List<DeadStockItem> items, String firmName) {
        StringBuilder sb = new StringBuilder();
        sb.append("Write a WhatsApp clearance broadcast for ").append(firmName).append(".\n\n");
        sb.append("These fabrics have been sitting in our stock for a while and need to move:\n\n");

        for (DeadStockItem item : items) {
            sb.append("• ").append(item.productName);
            if (item.category != null) sb.append(" (").append(item.category).append(")");
            sb.append(" — ₹").append(item.sellingPrice).append("/m");
            sb.append(", ").append(item.stockQty.setScale(0, java.math.RoundingMode.FLOOR)).append(" metres in stock");
            if (item.daysSinceLastSale > 0 && item.daysSinceLastSale < 999)
                sb.append(", last sold ").append(item.daysSinceLastSale).append(" days ago");
            else if (item.daysSinceLastSale >= 999)
                sb.append(", never sold");
            sb.append("\n");
        }

        sb.append("""

            Goals:
            - Create urgency without sounding desperate
            - Mention 2-3 specific products by name with their prices
            - Suggest a practical occasion or use (e.g. "perfect for upcoming Diwali orders")
            - End with a clear CTA: reply, visit, or WhatsApp to enquire
            - Do NOT mention a specific discount percentage unless it feels natural
            """);

        return sb.toString();
    }

    private String fallbackCaption(List<DeadStockItem> items, String firmName) {
        StringBuilder sb = new StringBuilder();
        sb.append("Clearance at ").append(firmName).append("!\n\n");
        for (int i = 0; i < Math.min(3, items.size()); i++) {
            DeadStockItem item = items.get(i);
            sb.append("• ").append(item.productName)
              .append(" — ₹").append(item.sellingPrice).append("/m\n");
        }
        sb.append("\nLimited stock. Reply to enquire.");
        return sb.toString();
    }

    // ── JDBC helpers ──────────────────────────────────────────────────────────

    private List<DeadStockItem> fetchDeadStock(String schema, int days, int limit) {
        List<DeadStockItem> list = new ArrayList<>();
        // Products with current stock > 0 where last bill was more than `days` ago (or never sold)
        String sql =
            "SELECT p.product_id, p.product_name, COALESCE(p.category,'Uncategorized'), " +
            "       p.selling_price, p.stock_quantity, " +
            "       COALESCE(EXTRACT(DAYS FROM NOW() - MAX(b.bill_date))::integer, 999) AS days_since, " +
            "       (SELECT image_url FROM product_images i " +
            "        WHERE i.product_id = p.product_id ORDER BY i.created_at ASC LIMIT 1) AS img " +
            "FROM products p " +
            "LEFT JOIN bill_items bi ON bi.product_id = p.product_id " +
            "LEFT JOIN bills     b  ON b.bill_id = bi.bill_id " +
            "WHERE p.is_active = TRUE AND p.stock_quantity > 0 " +
            "GROUP BY p.product_id, p.product_name, p.category, p.selling_price, p.stock_quantity " +
            "HAVING COALESCE(MAX(b.bill_date), '1970-01-01'::date) < NOW() - INTERVAL '" + days + " days' " +
            "ORDER BY days_since DESC " +
            "LIMIT " + limit;

        try (Connection c = dataSource.getConnection()) {
            c.createStatement().execute("SET search_path TO \"" + schema + "\", public");
            ResultSet rs = c.createStatement().executeQuery(sql);
            while (rs.next()) {
                DeadStockItem item = new DeadStockItem();
                item.productId          = rs.getInt("product_id");
                item.productName        = rs.getString("product_name");
                item.category           = rs.getString(3);
                item.sellingPrice       = rs.getBigDecimal("selling_price");
                item.stockQty           = rs.getBigDecimal("stock_quantity");
                item.daysSinceLastSale  = rs.getInt("days_since");
                item.imageUrl           = rs.getString("img");
                list.add(item);
            }
        } catch (Exception e) {
            System.err.println("[DeadStock] fetchDeadStock error: " + e.getMessage());
        }
        return list;
    }

    private List<String> fetchOptedInPhones(String schema) {
        List<String> phones = new ArrayList<>();
        try (Connection c = dataSource.getConnection()) {
            c.createStatement().execute("SET search_path TO \"" + schema + "\", public");
            ResultSet rs = c.createStatement().executeQuery(
                "SELECT phone FROM hermes_contacts WHERE opted_in = TRUE ORDER BY created_at");
            while (rs.next()) phones.add(rs.getString(1));
        } catch (Exception e) {
            System.err.println("[DeadStock] fetchOptedInPhones error: " + e.getMessage());
        }
        return phones;
    }

    private long saveCampaign(String schema, List<Integer> productIds,
                               String caption, String imageUrl) throws Exception {
        String sql =
            "INSERT INTO hermes_campaigns (campaign_type, product_ids, caption, image_url, status, scheduled_at) " +
            "VALUES ('dead_stock', ?, ?, ?, 'pending', NOW()) RETURNING id";
        try (Connection c = dataSource.getConnection()) {
            c.createStatement().execute("SET search_path TO \"" + schema + "\", public");
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setArray(1, c.createArrayOf("integer",
                    productIds.stream().map(Object.class::cast).toArray()));
                ps.setString(2, caption);
                ps.setString(3, imageUrl);
                ResultSet rs = ps.executeQuery();
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void updateCampaignStatus(String schema, long id, String status, int sent, int failed) {
        try (Connection c = dataSource.getConnection()) {
            c.createStatement().execute("SET search_path TO \"" + schema + "\", public");
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE hermes_campaigns SET status=?, sent_count=?, failed_count=?, executed_at=NOW() WHERE id=?")) {
                ps.setString(1, status);
                ps.setInt(2, sent);
                ps.setInt(3, failed);
                ps.setLong(4, id);
                ps.executeUpdate();
            }
        } catch (Exception e) {
            System.err.println("[DeadStock] updateCampaignStatus error: " + e.getMessage());
        }
    }

    // ── Inner record ──────────────────────────────────────────────────────────

    private static class DeadStockItem {
        int productId;
        String productName;
        String category;
        BigDecimal sellingPrice;
        BigDecimal stockQty;
        int daysSinceLastSale;
        String imageUrl;
    }
}

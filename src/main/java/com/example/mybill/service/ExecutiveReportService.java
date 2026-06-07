package com.example.mybill.service;

import com.example.mybill.multitenancy.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;

/**
 * Hermes Phase 2 — Executive Daily Report.
 *
 * Collects real revenue data (today / yesterday / month),
 * top sellers, slow movers, low stock, and demand gaps —
 * then asks Claude to write a crisp morning WhatsApp briefing for the owner.
 *
 * Called by CampaignSchedulerService every morning at 8am.
 */
@Service
public class ExecutiveReportService {

    @Autowired private ClaudeService claudeService;
    @Autowired private BroadcastTriggerService broadcastService;
    @Autowired private ChatSummaryService chatSummaryService;
    @Autowired private DataSource dataSource;

    // ── Public entry point ────────────────────────────────────────────────────

    /**
     * Build and send the daily briefing for one firm.
     * Sets/clears TenantContext internally.
     */
    public void send(String schema, String firmName, String firmCode, String ownerPhone) {
        if (ownerPhone == null || ownerPhone.isBlank()) {
            System.out.println("[ExecReport] No owner phone set for " + firmCode + " — skipping");
            return;
        }
        try {
            TenantContext.setCurrentTenant(schema);
            ReportData data = collect(schema);
            String report = buildReport(firmName, data);
            boolean sent = broadcastService.sendTextToPhone(firmCode, ownerPhone, report);
            System.out.println("[ExecReport] Daily report " + (sent ? "sent" : "FAILED") + " for " + firmCode);
        } catch (Exception e) {
            System.err.println("[ExecReport] Failed for " + firmCode + ": " + e.getMessage());
        } finally {
            TenantContext.clear();
        }
    }

    // ── Data collection ───────────────────────────────────────────────────────

    private ReportData collect(String schema) {
        ReportData d = new ReportData();
        try (Connection c = dataSource.getConnection()) {
            c.createStatement().execute("SET search_path TO \"" + schema + "\", public");

            fetchRevenueSummary(c, d);
            fetchTopProductsToday(c, d);
            fetchSlowMovers(c, d, 30);
            fetchLowStock(c, d);
        } catch (Exception e) {
            System.err.println("[ExecReport] collect error: " + e.getMessage());
        }

        // Demand gaps from Claude-cached chat summary (won't re-run Claude unless cache expired)
        fetchDemandGaps(schema, d);

        return d;
    }

    private void fetchRevenueSummary(Connection c, ReportData d) throws Exception {
        // Today
        try (ResultSet rs = c.createStatement().executeQuery(
            "SELECT COALESCE(SUM(total_amount),0), COUNT(*) FROM bills WHERE bill_date = CURRENT_DATE")) {
            if (rs.next()) { d.todayRevenue = rs.getBigDecimal(1); d.todayBills = rs.getInt(2); }
        }
        // Yesterday
        try (ResultSet rs = c.createStatement().executeQuery(
            "SELECT COALESCE(SUM(total_amount),0), COUNT(*) FROM bills WHERE bill_date = CURRENT_DATE - 1")) {
            if (rs.next()) { d.yesterdayRevenue = rs.getBigDecimal(1); d.yesterdayBills = rs.getInt(2); }
        }
        // This month
        try (ResultSet rs = c.createStatement().executeQuery(
            "SELECT COALESCE(SUM(total_amount),0), COUNT(*) FROM bills " +
            "WHERE DATE_TRUNC('month', bill_date) = DATE_TRUNC('month', CURRENT_DATE)")) {
            if (rs.next()) { d.monthRevenue = rs.getBigDecimal(1); d.monthBills = rs.getInt(2); }
        }
    }

    private void fetchTopProductsToday(Connection c, ReportData d) throws Exception {
        String sql =
            "SELECT p.product_name, SUM(bi.quantity) AS qty, SUM(bi.total_price) AS rev " +
            "FROM bill_items bi " +
            "JOIN products p ON p.product_id = bi.product_id " +
            "JOIN bills    b ON b.bill_id    = bi.bill_id " +
            "WHERE b.bill_date = CURRENT_DATE AND bi.product_id IS NOT NULL " +
            "GROUP BY p.product_id, p.product_name " +
            "ORDER BY rev DESC LIMIT 3";
        try (ResultSet rs = c.createStatement().executeQuery(sql)) {
            while (rs.next()) {
                d.topProductsToday.add(new ProductStat(
                    rs.getString("product_name"),
                    rs.getBigDecimal("qty"),
                    rs.getBigDecimal("rev")
                ));
            }
        }
    }

    private void fetchSlowMovers(Connection c, ReportData d, int days) throws Exception {
        // Products with stock > 0 but zero sales in the last `days` days — top 3 by stock value
        String sql =
            "SELECT p.product_name, p.selling_price, p.stock_quantity, " +
            "       COALESCE(EXTRACT(DAYS FROM NOW() - MAX(b.bill_date))::integer, 999) AS days_idle " +
            "FROM products p " +
            "LEFT JOIN bill_items bi ON bi.product_id = p.product_id " +
            "LEFT JOIN bills b       ON b.bill_id = bi.bill_id " +
            "WHERE p.is_active = TRUE AND p.stock_quantity > 0 " +
            "GROUP BY p.product_id, p.product_name, p.selling_price, p.stock_quantity " +
            "HAVING COALESCE(MAX(b.bill_date), '1970-01-01'::date) < NOW() - INTERVAL '" + days + " days' " +
            "ORDER BY (p.selling_price * p.stock_quantity) DESC LIMIT 3";

        try (ResultSet rs = c.createStatement().executeQuery(sql)) {
            while (rs.next()) {
                d.slowMovers.add(new SlowMover(
                    rs.getString("product_name"),
                    rs.getBigDecimal("selling_price"),
                    rs.getBigDecimal("stock_quantity"),
                    rs.getInt("days_idle")
                ));
            }
        }
    }

    private void fetchLowStock(Connection c, ReportData d) throws Exception {
        String sql =
            "SELECT product_name, stock_quantity, min_stock_level " +
            "FROM products " +
            "WHERE is_active = TRUE AND min_stock_level > 0 AND stock_quantity <= min_stock_level " +
            "ORDER BY stock_quantity ASC LIMIT 5";
        try (ResultSet rs = c.createStatement().executeQuery(sql)) {
            while (rs.next()) {
                d.lowStock.add(new LowStockItem(
                    rs.getString("product_name"),
                    rs.getBigDecimal("stock_quantity"),
                    rs.getBigDecimal("min_stock_level")
                ));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void fetchDemandGaps(String schema, ReportData d) {
        try {
            Map<String, Object> summary = chatSummaryService.getSummary("week", false);
            if (summary.containsKey("demandGaps")) {
                List<Map<String, Object>> gaps = (List<Map<String, Object>>) summary.get("demandGaps");
                if (gaps != null) {
                    for (Map<String, Object> g : gaps) {
                        String topic = (String) g.get("topic");
                        if (topic != null && !topic.isBlank()) d.demandGaps.add(topic);
                    }
                }
            }
            if (summary.containsKey("actions")) {
                List<Map<String, Object>> actions = (List<Map<String, Object>>) summary.get("actions");
                if (actions != null && !actions.isEmpty()) {
                    Object action = actions.get(0).get("action");
                    if (action != null) d.topChatAction = action.toString();
                }
            }
        } catch (Exception e) {
            System.err.println("[ExecReport] fetchDemandGaps error: " + e.getMessage());
        }
    }

    // ── Report generation ─────────────────────────────────────────────────────

    private String buildReport(String firmName, ReportData d) {
        try {
            return claudeService.complete(systemPrompt(), userPrompt(firmName, d));
        } catch (Exception e) {
            System.err.println("[ExecReport] Claude failed, using fallback: " + e.getMessage());
            return fallbackReport(firmName, d);
        }
    }

    private String systemPrompt() {
        return """
            You are a sharp business advisor for Indian fabric retail boutiques.
            Write a concise morning WhatsApp message for the shop owner — like a smart assistant
            giving a quick daily briefing before the shop opens.

            Rules:
            - Maximum 250 words.
            - Start with "Good morning [firmName]!" on the first line.
            - Use bullet points for data. Be specific — real numbers, real product names.
            - Highlight one clear priority action for today.
            - Tone: direct, warm, confident. No corporate buzzwords.
            - No emojis except 1-2 if they genuinely add clarity.
            - Respond with ONLY the WhatsApp message text.
            """;
    }

    private String userPrompt(String firmName, ReportData d) {
        StringBuilder sb = new StringBuilder();
        sb.append("Write the morning briefing for ").append(firmName).append(".\n\n");

        // Revenue
        sb.append("REVENUE DATA:\n");
        sb.append("• Today so far: ₹").append(fmt(d.todayRevenue))
          .append(" across ").append(d.todayBills).append(" bills\n");
        sb.append("• Yesterday: ₹").append(fmt(d.yesterdayRevenue))
          .append(" across ").append(d.yesterdayBills).append(" bills\n");
        sb.append("• This month: ₹").append(fmt(d.monthRevenue))
          .append(" across ").append(d.monthBills).append(" bills\n");

        // Top sellers today
        if (!d.topProductsToday.isEmpty()) {
            sb.append("\nTOP SELLERS TODAY:\n");
            for (ProductStat p : d.topProductsToday)
                sb.append("• ").append(p.name).append(" — ₹").append(fmt(p.revenue))
                  .append(" (").append(p.qty.setScale(1, java.math.RoundingMode.HALF_UP)).append("m)\n");
        } else {
            sb.append("\nNo bills entered today yet.\n");
        }

        // Slow movers
        if (!d.slowMovers.isEmpty()) {
            sb.append("\nSLOW MOVERS (sitting stock — potential cashflow risk):\n");
            for (SlowMover s : d.slowMovers) {
                sb.append("• ").append(s.name).append(" — ₹").append(fmt(s.price)).append("/m, ");
                sb.append(s.stock.setScale(0, java.math.RoundingMode.FLOOR)).append("m in stock, ");
                sb.append(s.daysIdle < 999 ? s.daysIdle + " days without a sale" : "never sold").append("\n");
            }
        }

        // Low stock alerts
        if (!d.lowStock.isEmpty()) {
            sb.append("\nLOW STOCK ALERTS:\n");
            for (LowStockItem ls : d.lowStock)
                sb.append("• ").append(ls.name).append(" — ").append(fmt(ls.stock)).append("m left (min: ")
                  .append(fmt(ls.minLevel)).append("m)\n");
        }

        // Demand gaps from customer chat
        if (!d.demandGaps.isEmpty()) {
            sb.append("\nCUSTOMERS ASKING FOR (but not finding in stock):\n");
            for (String gap : d.demandGaps.subList(0, Math.min(3, d.demandGaps.size())))
                sb.append("• ").append(gap).append("\n");
        }

        // Top chat action
        if (d.topChatAction != null) {
            sb.append("\nTOP RECOMMENDED ACTION FROM CHAT DATA:\n");
            sb.append("• ").append(d.topChatAction).append("\n");
        }

        sb.append("\nGenerate a 200-250 word WhatsApp morning briefing based on the above.");
        return sb.toString();
    }

    private String fallbackReport(String firmName, ReportData d) {
        StringBuilder sb = new StringBuilder();
        sb.append("Good morning ").append(firmName).append("!\n\n");
        sb.append("Today's Revenue: ₹").append(fmt(d.todayRevenue))
          .append(" (").append(d.todayBills).append(" bills)\n");
        sb.append("This Month: ₹").append(fmt(d.monthRevenue)).append("\n");
        if (!d.lowStock.isEmpty()) {
            sb.append("\nLow Stock:\n");
            d.lowStock.forEach(ls -> sb.append("• ").append(ls.name).append("\n"));
        }
        if (!d.demandGaps.isEmpty()) {
            sb.append("\nCustomers asking for: ").append(String.join(", ", d.demandGaps)).append("\n");
        }
        return sb.toString();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String fmt(BigDecimal v) {
        if (v == null) return "0";
        return String.format("%,.0f", v.doubleValue());
    }

    // ── Internal data classes ─────────────────────────────────────────────────

    private static class ReportData {
        BigDecimal todayRevenue     = BigDecimal.ZERO;
        int        todayBills       = 0;
        BigDecimal yesterdayRevenue = BigDecimal.ZERO;
        int        yesterdayBills   = 0;
        BigDecimal monthRevenue     = BigDecimal.ZERO;
        int        monthBills       = 0;

        List<ProductStat>  topProductsToday = new ArrayList<>();
        List<SlowMover>    slowMovers       = new ArrayList<>();
        List<LowStockItem> lowStock         = new ArrayList<>();
        List<String>       demandGaps       = new ArrayList<>();
        String             topChatAction    = null;
    }

    private record ProductStat(String name, BigDecimal qty, BigDecimal revenue) {}
    private record SlowMover(String name, BigDecimal price, BigDecimal stock, int daysIdle) {}
    private record LowStockItem(String name, BigDecimal stock, BigDecimal minLevel) {}
}

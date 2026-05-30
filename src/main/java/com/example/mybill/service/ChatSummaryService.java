package com.example.mybill.service;

import com.example.mybill.multitenancy.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.*;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class ChatSummaryService {

    @Autowired private DataSource dataSource;
    @Autowired private ClaudeService claudeService;

    private final ObjectMapper json = new ObjectMapper();

    // Cache valid for 6 hours
    private static final int CACHE_HOURS = 6;

    // ── Public API ──────────────────────────────────────────────────────────

    public Map<String, Object> getSummary(String period, boolean force) {
        String schema = TenantContext.getCurrentTenant();
        if (schema == null) return error("No tenant context");

        try (Connection conn = dataSource.getConnection()) {
            conn.createStatement().execute("SET search_path TO \"" + schema + "\", public");

            // Serve from cache unless forced
            if (!force) {
                Map<String, Object> cached = readCache(conn, period);
                if (cached != null) return cached;
            }

            // Check there are chat tables and enough data
            if (!chatTablesExist(conn, schema)) {
                return error("No chat data yet — start collecting customer conversations first.");
            }

            LocalDate from = periodStart(period);
            String context = buildDataContext(conn, schema, period, from);

            long totalQueries = totalQueriesCount(conn, from);
            if (totalQueries < 5) {
                return error("Not enough chat data to generate insights (" + totalQueries +
                             " queries). Insights appear once you have at least 5 customer conversations.");
            }

            String firmName = getFirmName(conn, schema);
            String raw = claudeService.complete(systemPrompt(), userPrompt(firmName, period, context));
            Map<String, Object> summary = parseClaudeJson(raw);

            // Add metadata
            summary.put("generatedAt", LocalDateTime.now().toString());
            summary.put("period",      period);
            summary.put("cached",      false);

            writeCache(conn, period, summary);
            return summary;

        } catch (Exception e) {
            System.err.println("[ChatSummary] Error: " + e.getMessage());
            return error("Failed to generate insights: " + e.getMessage());
        }
    }

    // ── Cache ───────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> readCache(Connection conn, String period) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT value FROM firm_settings WHERE key = ?")) {
            ps.setString(1, cacheKey(period));
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                Map<String, Object> cached = json.readValue(rs.getString(1), Map.class);
                String at = (String) cached.get("generatedAt");
                if (at != null && LocalDateTime.parse(at).isAfter(LocalDateTime.now().minusHours(CACHE_HOURS))) {
                    cached.put("cached", true);
                    return cached;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void writeCache(Connection conn, String period, Map<String, Object> summary) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO firm_settings (key, value) VALUES (?, ?) " +
                "ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value")) {
            ps.setString(1, cacheKey(period));
            ps.setString(2, json.writeValueAsString(summary));
            ps.executeUpdate();
        } catch (Exception e) {
            System.err.println("[ChatSummary] Cache write failed: " + e.getMessage());
        }
    }

    private String cacheKey(String period) { return "chat_summary_" + period; }

    // ── Data aggregation ────────────────────────────────────────────────────

    private String buildDataContext(Connection conn, String schema, String period, LocalDate from) throws Exception {
        java.sql.Date fromSql = java.sql.Date.valueOf(from);
        StringBuilder sb = new StringBuilder();
        sb.append("CUSTOMER CHAT DATA — ").append(period.toUpperCase())
          .append(" (from ").append(from).append(")\n\n");

        // --- Overview ---
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT COUNT(DISTINCT cs.session_id)," +
            "  COUNT(*) FILTER (WHERE cm.role='user')," +
            "  COUNT(*) FILTER (WHERE COALESCE(cs.channel,'web')='whatsapp')," +
            "  COUNT(*) FILTER (WHERE COALESCE(cs.channel,'web')='web') " +
            "FROM chat_messages cm JOIN chat_sessions cs ON cs.session_id=cm.session_id " +
            "WHERE cm.created_at >= ?")) {
            ps.setDate(1, fromSql);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                long sessions = rs.getLong(1), queries = rs.getLong(2),
                     wa = rs.getLong(3), web = rs.getLong(4);
                sb.append("OVERVIEW:\n");
                sb.append("• ").append(sessions).append(" customer sessions (")
                  .append(wa).append(" WhatsApp, ").append(web).append(" Web chatbot)\n");
                sb.append("• ").append(queries).append(" total customer queries\n");
            }
        } catch (Exception e) { sb.append("• Overview unavailable\n"); }

        // --- Unanswered queries ---
        List<String> unanswered = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
            "WITH pairs AS (" +
            "  SELECT role, content, created_at, session_id," +
            "    LEAD(products_shown) OVER (PARTITION BY session_id ORDER BY created_at) AS next_products," +
            "    LEAD(role) OVER (PARTITION BY session_id ORDER BY created_at) AS next_role " +
            "  FROM chat_messages WHERE created_at >= ?" +
            ") " +
            "SELECT content, COUNT(*) AS cnt FROM pairs " +
            "WHERE role='user' AND next_role='assistant' " +
            "AND content NOT ILIKE '%similar to this image%' " +
            "AND (next_products IS NULL OR jsonb_array_length(next_products)=0) " +
            "GROUP BY content ORDER BY cnt DESC LIMIT 20")) {
            ps.setDate(1, fromSql);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) unanswered.add("\"" + rs.getString(1) + "\" (" + rs.getLong(2) + "×)");
        } catch (Exception ignored) {}

        long unansweredCount = unanswered.size();
        sb.append("• ").append(unansweredCount > 0 ? unansweredCount + " unique queries got ZERO product results" : "All queries found matching products").append("\n\n");

        if (!unanswered.isEmpty()) {
            sb.append("WHAT CUSTOMERS COULDN'T FIND (no products matched — stock/catalogue gaps):\n");
            for (int i = 0; i < Math.min(15, unanswered.size()); i++)
                sb.append(i + 1).append(". ").append(unanswered.get(i)).append("\n");
            sb.append("\n");
        }

        // --- Top categories ---
        sb.append("TOP PRODUCT CATEGORIES CUSTOMERS SEARCHED FOR:\n");
        int catIdx = 1;
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT prod->>'categoryName', COUNT(*) " +
            "FROM chat_messages, jsonb_array_elements(products_shown) AS prod " +
            "WHERE products_shown IS NOT NULL AND created_at >= ? " +
            "AND (prod->>'categoryName') IS NOT NULL AND (prod->>'categoryName') != '' " +
            "GROUP BY 1 ORDER BY COUNT(*) DESC LIMIT 10")) {
            ps.setDate(1, fromSql);
            ResultSet rs = ps.executeQuery();
            while (rs.next())
                sb.append(catIdx++).append(". ").append(rs.getString(1))
                  .append(" — ").append(rs.getLong(2)).append(" times\n");
        } catch (Exception ignored) {}
        if (catIdx == 1) sb.append("No category data available\n");
        sb.append("\n");

        // --- Top requested products + stock ---
        sb.append("MOST REQUESTED PRODUCTS (with current stock level):\n");
        int prodIdx = 1;
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT t.product_name, t.times_shown, COALESCE(p.stock_quantity, -1) " +
            "FROM (" +
            "  SELECT prod->>'productName' AS product_name," +
            "         (prod->>'productId')::integer AS product_id," +
            "         COUNT(*) AS times_shown " +
            "  FROM chat_messages, jsonb_array_elements(products_shown) AS prod " +
            "  WHERE products_shown IS NOT NULL AND created_at >= ? " +
            "  GROUP BY 1,2 ORDER BY COUNT(*) DESC LIMIT 10" +
            ") t LEFT JOIN products p ON p.product_id = t.product_id " +
            "ORDER BY t.times_shown DESC")) {
            ps.setDate(1, fromSql);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                double stock = rs.getDouble(3);
                String stockLabel = stock < 0 ? "unknown stock" :
                                    stock == 0 ? "OUT OF STOCK" :
                                    stock < 5  ? "LOW STOCK (" + (int)stock + " left)" :
                                                 "in stock (" + (int)stock + ")";
                sb.append(prodIdx++).append(". ").append(rs.getString(1))
                  .append(" — shown ").append(rs.getLong(2)).append("×, ")
                  .append(stockLabel).append("\n");
            }
        } catch (Exception ignored) {}
        if (prodIdx == 1) sb.append("No product request data available\n");
        sb.append("\n");

        // --- Image searches ---
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT COUNT(*) FROM chat_messages " +
            "WHERE created_at >= ? AND role='user' AND image_url IS NOT NULL")) {
            ps.setDate(1, fromSql);
            ResultSet rs = ps.executeQuery();
            if (rs.next() && rs.getLong(1) > 0) {
                sb.append("IMAGE SEARCHES: ").append(rs.getLong(1))
                  .append(" customers uploaded photos to find similar fabrics\n\n");
            }
        } catch (Exception ignored) {}

        return sb.toString();
    }

    private long totalQueriesCount(Connection conn, LocalDate from) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM chat_messages WHERE role='user' AND created_at >= ?")) {
            ps.setDate(1, java.sql.Date.valueOf(from));
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getLong(1) : 0;
        } catch (Exception e) { return 0; }
    }

    // ── Claude prompt ───────────────────────────────────────────────────────

    private String systemPrompt() {
        return """
            You are a sharp business intelligence analyst specialising in Indian fabric retail stores.
            Your job is to analyse customer chat data and produce concise, actionable insights for the shop owner.
            You understand Indian textiles, customer buying behaviour, seasonal trends, and e-commerce conversion.

            IMPORTANT: Respond with ONLY a valid JSON object. No markdown, no code blocks, no explanation outside the JSON.
            Every string value must be concise (1-2 sentences max). Be specific — name actual fabrics/categories from the data.
            """;
    }

    private String userPrompt(String firmName, String period, String dataContext) {
        return dataContext + "\n\n" +
            "Analyse the above data for " + firmName + " and return ONLY this JSON (fill all fields):\n\n" +
            "{\n" +
            "  \"headline\": \"2-3 sentence executive summary of the most important finding\",\n" +
            "  \"demandGaps\": [\n" +
            "    {\"topic\": \"specific fabric/category name\", \"insight\": \"why this is a gap and opportunity\"}\n" +
            "  ],\n" +
            "  \"actions\": [\n" +
            "    {\"priority\": 1, \"action\": \"specific thing to do\", \"impact\": \"expected result\"}\n" +
            "  ],\n" +
            "  \"stockAlerts\": [\n" +
            "    {\"product\": \"name\", \"urgency\": \"high or medium\", \"insight\": \"demand vs stock situation\"}\n" +
            "  ],\n" +
            "  \"trustScore\": {\"score\": \"low or medium or high\", \"reason\": \"one sentence why\"},\n" +
            "  \"channelInsight\": \"one insight about WhatsApp vs Web chatbot customer behaviour\",\n" +
            "  \"imageInsight\": \"one insight about image search patterns, or null if fewer than 3 image searches\"\n" +
            "}\n\n" +
            "Rules:\n" +
            "- demandGaps: list top 3-5 specific fabrics/categories customers want but can't find\n" +
            "- actions: exactly 5 prioritised actions (priority 1 = most urgent)\n" +
            "- stockAlerts: only products with high demand AND low/out of stock; omit if none\n" +
            "- trustScore: low if unanswered rate > 30%, high if < 10%, otherwise medium\n" +
            "- Be direct and specific. Mention real fabric names from the data.";
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseClaudeJson(String raw) {
        try {
            // Strip markdown code fences if present
            String cleaned = raw.trim();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("^```[a-z]*\\n?", "").replaceAll("```$", "").trim();
            }
            return json.readValue(cleaned, Map.class);
        } catch (Exception e) {
            System.err.println("[ChatSummary] JSON parse failed: " + e.getMessage());
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("headline", raw.length() > 300 ? raw.substring(0, 300) : raw);
            fallback.put("parseError", true);
            return fallback;
        }
    }

    private String getFirmName(Connection conn, String schema) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT firm_name FROM public.firms WHERE schema_name = ?")) {
            ps.setString(1, schema);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getString(1) : schema;
        } catch (Exception e) { return schema; }
    }

    private boolean chatTablesExist(Connection conn, String schema) {
        try (ResultSet rs = conn.createStatement().executeQuery(
                "SELECT EXISTS(SELECT 1 FROM information_schema.tables " +
                "WHERE table_schema='" + schema + "' AND table_name='chat_messages')")) {
            return rs.next() && rs.getBoolean(1);
        } catch (Exception e) { return false; }
    }

    private LocalDate periodStart(String period) {
        LocalDate today = LocalDate.now();
        return switch (period) {
            case "today" -> today;
            case "week"  -> today.with(DayOfWeek.MONDAY);
            case "year"  -> today.withDayOfYear(1);
            default      -> today.withDayOfMonth(1);
        };
    }

    private Map<String, Object> error(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", msg);
        return m;
    }
}

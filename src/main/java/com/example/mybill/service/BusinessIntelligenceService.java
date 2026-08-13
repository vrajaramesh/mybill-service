package com.example.mybill.service;

import com.example.mybill.multitenancy.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;

@Service
public class BusinessIntelligenceService {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ClaudeService claudeService;

    @Value("${serp.api.key:}")
    private String serpApiKey;

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    public Map<String, Object> generateReport(Map<String, Object> extraInput) {
        String schema = TenantContext.getCurrentTenant();
        if (schema == null) schema = "public";

        LocalDate today = LocalDate.now();
        LocalDate thirtyDaysAgo = today.minusDays(30);

        Map<String, Object> salesSummary = getSalesSummary(schema, thirtyDaysAgo, today);
        List<Map<String, Object>> topProducts = getTopProducts(schema, thirtyDaysAgo, today);
        List<Map<String, Object>> deadStock = getDeadStock(schema, thirtyDaysAgo, today);
        Map<String, Object> purchaseSummary = getPurchaseSummary(schema, thirtyDaysAgo, today);
        Map<String, Object> expenseSummary = getExpenseSummary(schema, thirtyDaysAgo, today);
        Map<String, Object> enquiryData = getEnquiryData(schema, thirtyDaysAgo, today);
        List<Map<String, Object>> trends = getInstagramTrends();

        String report = generateAiReport(salesSummary, topProducts, deadStock,
            purchaseSummary, expenseSummary, enquiryData, trends, extraInput);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("salesSummary", salesSummary);
        result.put("topProducts", topProducts);
        result.put("deadStock", deadStock);
        result.put("purchaseSummary", purchaseSummary);
        result.put("expenseSummary", expenseSummary);
        result.put("enquiryData", enquiryData);
        result.put("trendingFabrics", trends);
        result.put("aiReport", report);
        result.put("generatedAt", today.toString());
        return result;
    }

    private Map<String, Object> getSalesSummary(String schema, LocalDate from, LocalDate to) {
        try {
            return jdbcTemplate.queryForMap(
                "SELECT COUNT(*) as total_bills, " +
                "COALESCE(SUM(total_amount), 0) as total_revenue, " +
                "COALESCE(SUM(gst_amount), 0) as total_gst, " +
                "COALESCE(AVG(total_amount), 0) as avg_bill_value, " +
                "COUNT(DISTINCT customer_id) as unique_customers " +
                "FROM \"" + schema + "\".bills " +
                "WHERE bill_date >= ? AND bill_date <= ?",
                from, to
            );
        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }
    }

    private List<Map<String, Object>> getTopProducts(String schema, LocalDate from, LocalDate to) {
        try {
            return jdbcTemplate.queryForList(
                "SELECT p.product_name, p.category, " +
                "COALESCE(SUM(bi.quantity), 0) as qty_sold, " +
                "COALESCE(SUM(bi.total_price), 0) as revenue " +
                "FROM \"" + schema + "\".products p " +
                "JOIN \"" + schema + "\".bill_items bi ON bi.product_id = p.product_id " +
                "JOIN \"" + schema + "\".bills b ON b.bill_id = bi.bill_id " +
                "WHERE b.bill_date >= ? AND b.bill_date <= ? " +
                "GROUP BY p.product_id, p.product_name, p.category " +
                "ORDER BY revenue DESC LIMIT 15",
                from, to
            );
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<Map<String, Object>> getDeadStock(String schema, LocalDate from, LocalDate to) {
        try {
            return jdbcTemplate.queryForList(
                "SELECT p.product_name, p.category, p.stock_quantity, p.selling_price, " +
                "COALESCE(p.stock_quantity * p.selling_price, 0) as stock_value, " +
                "COALESCE(SUM(bi.quantity), 0) as qty_sold_30d " +
                "FROM \"" + schema + "\".products p " +
                "LEFT JOIN \"" + schema + "\".bill_items bi ON bi.product_id = p.product_id " +
                "  AND bi.bill_id IN (SELECT bill_id FROM \"" + schema + "\".bills WHERE bill_date >= ?) " +
                "WHERE p.is_active = TRUE AND p.stock_quantity > 0 " +
                "GROUP BY p.product_id, p.product_name, p.category, p.stock_quantity, p.selling_price " +
                "HAVING COALESCE(SUM(bi.quantity), 0) = 0 " +
                "ORDER BY stock_value DESC LIMIT 20",
                from
            );
        } catch (Exception e) {
            return List.of();
        }
    }

    private Map<String, Object> getPurchaseSummary(String schema, LocalDate from, LocalDate to) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=? AND table_name='purchases'",
                Integer.class, schema
            );
            if (count == null || count == 0) return Map.of("total_purchases", 0, "total_amount", 0);

            return jdbcTemplate.queryForMap(
                "SELECT COUNT(*) as total_purchases, " +
                "COALESCE(SUM(final_amount), 0) as total_amount, " +
                "COALESCE(SUM(paid_amount), 0) as total_paid " +
                "FROM \"" + schema + "\".purchases " +
                "WHERE invoice_date >= ? AND invoice_date <= ?",
                from, to
            );
        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }
    }

    private Map<String, Object> getExpenseSummary(String schema, LocalDate from, LocalDate to) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=? AND table_name='expenses'",
                Integer.class, schema
            );
            if (count == null || count == 0) return Map.of("note", "no expenses recorded");

            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT category, COALESCE(SUM(amount), 0) as total " +
                "FROM \"" + schema + "\".expenses " +
                "WHERE expense_date >= ? AND expense_date <= ? " +
                "GROUP BY category ORDER BY total DESC",
                from, to
            );

            Map<String, Object> result = new LinkedHashMap<>();
            double grandTotal = 0;
            for (Map<String, Object> r : rows) {
                double amt = ((Number) r.get("total")).doubleValue();
                result.put((String) r.get("category"), amt);
                grandTotal += amt;
            }
            result.put("TOTAL", grandTotal);
            return result;
        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }
    }

    private Map<String, Object> getEnquiryData(String schema, LocalDate from, LocalDate to) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=? AND table_name='chat_messages'",
                Integer.class, schema
            );
            if (count == null || count == 0) return Map.of("total_enquiries", 0);

            Map<String, Object> stats = jdbcTemplate.queryForMap(
                "SELECT COUNT(*) as total_messages, COUNT(DISTINCT session_id) as total_sessions " +
                "FROM \"" + schema + "\".chat_messages " +
                "WHERE created_at >= ? AND role = 'user'",
                java.sql.Timestamp.valueOf(from.atStartOfDay())
            );

            List<Map<String, Object>> samples = jdbcTemplate.queryForList(
                "SELECT content FROM \"" + schema + "\".chat_messages " +
                "WHERE created_at >= ? AND role = 'user' " +
                "ORDER BY created_at DESC LIMIT 30",
                java.sql.Timestamp.valueOf(from.atStartOfDay())
            );

            List<String> msgs = new ArrayList<>();
            for (Map<String, Object> s : samples) {
                Object content = s.get("content");
                if (content != null) msgs.add(content.toString());
            }

            Map<String, Object> result = new LinkedHashMap<>(stats);
            result.put("sample_messages", msgs);
            return result;
        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }
    }

    public List<Map<String, Object>> getInstagramTrends() {
        if (serpApiKey == null || serpApiKey.isBlank()) {
            return List.of(Map.of("note", "SerpAPI key not configured. Add SERP_API_KEY env var to enable Instagram trend analysis."));
        }

        List<Map<String, Object>> allResults = new ArrayList<>();
        String[] queries = {
            "trending saree fabric designs 2026 india instagram",
            "trending fabric wholesale manufacturers india 2026",
            "latest cotton silk fabric trends india supplier contact"
        };

        for (String query : queries) {
            try {
                String encoded = java.net.URLEncoder.encode(query, "UTF-8");
                String url = "https://serpapi.com/search.json?q=" + encoded +
                    "&api_key=" + serpApiKey + "&num=5&gl=in&hl=en";

                HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .GET().build();

                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                JsonNode root = mapper.readTree(resp.body());

                JsonNode organic = root.path("organic_results");
                if (organic.isArray()) {
                    for (JsonNode item : organic) {
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("title", item.path("title").asText());
                        r.put("snippet", item.path("snippet").asText());
                        r.put("link", item.path("link").asText());
                        r.put("query", query);
                        allResults.add(r);
                    }
                }
            } catch (Exception e) {
                allResults.add(Map.of("error", "SerpAPI query failed: " + e.getMessage(), "query", query));
            }
        }
        return allResults;
    }

    private String getFestivalCalendar() {
        LocalDate today = LocalDate.now();
        int year = today.getYear();

        // Approximate fixed/recurring festival dates (update year dynamically)
        List<String[]> festivals = new ArrayList<>(List.of(
            new String[]{"Onam",               year + "-09-05"},
            new String[]{"Navratri/Durga Puja", year + "-10-02"},
            new String[]{"Dussehra",            year + "-10-12"},
            new String[]{"Diwali",              year + "-10-20"},
            new String[]{"Bhai Dooj",           year + "-10-22"},
            new String[]{"Christmas",           year + "-12-25"},
            new String[]{"Pongal/Makar Sankranti", (year + 1) + "-01-14"},
            new String[]{"Republic Day",        (year + 1) + "-01-26"},
            new String[]{"Valentine's Day",     (year + 1) + "-02-14"},
            new String[]{"Holi",                (year + 1) + "-03-14"},
            new String[]{"Ugadi/Gudi Padwa",    (year + 1) + "-03-30"},
            new String[]{"Eid-ul-Fitr",         (year + 1) + "-04-01"},
            new String[]{"Akshaya Tritiya",     (year + 1) + "-04-28"},
            new String[]{"Raksha Bandhan",      (year + 1) + "-08-09"},
            new String[]{"Eid-ul-Adha",         (year + 1) + "-06-07"},
            new String[]{"Independence Day",    (year + 1) + "-08-15"}
        ));

        StringBuilder sb = new StringBuilder();
        sb.append("### Upcoming Indian Festivals (next 6 months)\n");
        sb.append("Today: ").append(today).append("\n\n");

        LocalDate sixMonthsLater = today.plusMonths(6);
        for (String[] f : festivals) {
            try {
                LocalDate fd = LocalDate.parse(f[1]);
                if (!fd.isBefore(today) && !fd.isAfter(sixMonthsLater)) {
                    long daysAway = java.time.temporal.ChronoUnit.DAYS.between(today, fd);
                    LocalDate stockBy = fd.minusDays(30);
                    sb.append("- **").append(f[0]).append("** (").append(fd)
                      .append(", ").append(daysAway).append(" days away) — stock by ")
                      .append(stockBy).append("\n");
                }
            } catch (Exception ignored) {}
        }
        return sb.toString();
    }

    private String generateAiReport(Map<String, Object> sales, List<Map<String, Object>> topProducts,
                                    List<Map<String, Object>> deadStock, Map<String, Object> purchases,
                                    Map<String, Object> expenses, Map<String, Object> enquiries,
                                    List<Map<String, Object>> trends, Map<String, Object> extraInput) {
        String systemPrompt = """
            You are a senior business consultant specialising in Indian textile/fabric retail businesses.
            Analyse the business data provided and give a comprehensive, actionable report.
            Be specific with numbers. Use INR (₹) for currency.
            Structure your response with clear markdown sections using ## headings.
            """;

        StringBuilder prompt = new StringBuilder();
        prompt.append("## Business Data for Last 30 Days\n\n");

        prompt.append("### Sales Performance\n");
        prompt.append(formatMap(sales)).append("\n\n");

        prompt.append("### Top 15 Products by Revenue\n");
        for (Map<String, Object> p : topProducts) {
            prompt.append("- ").append(p.get("product_name"))
                .append(": ₹").append(p.get("revenue"))
                .append(" (qty: ").append(p.get("qty_sold")).append(")\n");
        }
        prompt.append("\n");

        prompt.append("### Dead Stock (Products with stock but ZERO sales in 30 days)\n");
        for (Map<String, Object> d : deadStock) {
            prompt.append("- ").append(d.get("product_name"))
                .append(": stock=").append(d.get("stock_quantity"))
                .append(", value=₹").append(d.get("stock_value")).append("\n");
        }
        prompt.append("\n");

        prompt.append("### Purchases (30 days)\n");
        prompt.append(formatMap(purchases)).append("\n\n");

        prompt.append("### Expenses Breakdown (30 days)\n");
        prompt.append(formatMap(expenses)).append("\n\n");

        prompt.append("### Customer Enquiries (ecom chatbot)\n");
        prompt.append("Total sessions: ").append(enquiries.get("total_sessions"))
            .append(", Total messages: ").append(enquiries.get("total_messages")).append("\n");

        @SuppressWarnings("unchecked")
        List<String> sampleMsgs = (List<String>) enquiries.getOrDefault("sample_messages", List.of());
        if (!sampleMsgs.isEmpty()) {
            prompt.append("Sample customer enquiries:\n");
            sampleMsgs.stream().limit(15).forEach(m -> prompt.append("  - ").append(m).append("\n"));
        }
        prompt.append("\n");

        if (extraInput != null && !extraInput.isEmpty()) {
            prompt.append("### Additional Business Context Provided by Owner\n");
            prompt.append(formatMap(extraInput)).append("\n\n");
        }

        if (!trends.isEmpty() && !trends.get(0).containsKey("note")) {
            prompt.append("### Instagram/Market Trend Data\n");
            for (Map<String, Object> t : trends) {
                if (!t.containsKey("error")) {
                    prompt.append("- **").append(t.get("title")).append("**: ")
                        .append(t.get("snippet")).append("\n");
                }
            }
            prompt.append("\n");
        }

        prompt.append(getFestivalCalendar()).append("\n");

        prompt.append("""
            ---
            Based on the above data, provide:

            ## 1. Executive Summary
            (Revenue, total costs, net profit/loss, key highlights)

            ## 2. Sales Analysis
            (Trends, peak products, customer buying patterns)

            ## 3. Dead Stock Action Plan
            (Which products to discount, bundle, or return to supplier)

            ## 4. Business Gaps & Weaknesses
            (What's lagging, where improvement is needed)

            ## 5. Marketing Strategies
            (Specific actionable strategies for next 30 days)

            ## 6. Inventory Recommendations
            (What to restock, what to stop buying)

            ## 7. Customer Insights from Enquiries
            (What customers are asking for that you don't have)

            ## 8. Trending Fabrics & Manufacturer Leads
            (Based on trend data, which fabrics to source and suggested manufacturers)

            ## 9. Festival Forecasting & Advance Stocking Plan
            For each upcoming festival listed above (within next 6 months), specify:
            - Which fabric types and saree styles traditionally sell best for that festival
            - Recommended stock quantities to procure 30 days before the festival
            - Specific marketing actions to take 2 weeks before each festival
            - Price points and offers that work well for each festival

            ## 10. Priority Action Items
            (Top 5 things to do THIS WEEK, ranked by impact)
            """);

        try {
            return claudeService.completeBI(systemPrompt, prompt.toString());
        } catch (Exception e) {
            return "Error generating AI report: " + e.getMessage();
        }
    }

    private String formatMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) return "No data";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!"sample_messages".equals(e.getKey())) {
                sb.append("- ").append(e.getKey()).append(": ").append(e.getValue()).append("\n");
            }
        }
        return sb.toString();
    }
}

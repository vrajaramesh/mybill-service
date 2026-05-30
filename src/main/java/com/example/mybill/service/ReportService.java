package com.example.mybill.service;

import com.example.mybill.multitenancy.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.*;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ReportService {

    @Autowired
    private DataSource dataSource;

    @PersistenceContext
    private EntityManager em;

    @SuppressWarnings("unchecked")
    public Map<String, Object> getOverview(int year) {
        Object[] r = (Object[]) em.createNativeQuery(
            "SELECT COUNT(*), COALESCE(SUM(total_amount),0), COALESCE(SUM(gst_amount),0), COALESCE(AVG(total_amount),0) " +
            "FROM bills WHERE EXTRACT(YEAR FROM bill_date) = :year"
        ).setParameter("year", year).getSingleResult();

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalBills",    ((Number) r[0]).longValue());
        m.put("totalSales",    r[1]);
        m.put("totalGST",      r[2]);
        m.put("avgBillValue",  r[3]);
        return m;
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getMonthlySales(int year) {
        List<Object[]> rows = em.createNativeQuery(
            "SELECT EXTRACT(MONTH FROM bill_date), SUM(total_amount), COUNT(*), SUM(gst_amount) " +
            "FROM bills WHERE EXTRACT(YEAR FROM bill_date) = :year " +
            "GROUP BY 1 ORDER BY 1"
        ).setParameter("year", year).getResultList();

        return rows.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("month",       ((Number) r[0]).intValue());
            m.put("totalAmount", r[1]);
            m.put("billCount",   ((Number) r[2]).longValue());
            m.put("gstAmount",   r[3]);
            return m;
        }).collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getTopProducts(int year, int limit) {
        List<Object[]> rows = em.createNativeQuery(
            "SELECT p.product_name, COALESCE(p.category, 'Uncategorized'), " +
            "       SUM(bi.quantity), SUM(bi.total_price) " +
            "FROM bill_items bi " +
            "JOIN products p ON bi.product_id = p.product_id " +
            "JOIN bills b    ON bi.bill_id = b.bill_id " +
            "WHERE EXTRACT(YEAR FROM b.bill_date) = :year AND bi.product_id IS NOT NULL " +
            "GROUP BY p.product_id, p.product_name, p.category " +
            "ORDER BY SUM(bi.total_price) DESC " +
            "LIMIT :lim"
        ).setParameter("year", year).setParameter("lim", limit).getResultList();

        return rows.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("productName",  r[0]);
            m.put("category",     r[1]);
            m.put("quantitySold", r[2]);
            m.put("revenue",      r[3]);
            return m;
        }).collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getCategoryRevenue(int year) {
        List<Object[]> rows = em.createNativeQuery(
            "SELECT COALESCE(p.category, 'Uncategorized'), SUM(bi.total_price) " +
            "FROM bill_items bi " +
            "JOIN products p ON bi.product_id = p.product_id " +
            "JOIN bills b    ON bi.bill_id = b.bill_id " +
            "WHERE EXTRACT(YEAR FROM b.bill_date) = :year AND bi.product_id IS NOT NULL " +
            "GROUP BY 1 ORDER BY 2 DESC"
        ).setParameter("year", year).getResultList();

        return rows.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("category", r[0]);
            m.put("revenue",  r[1]);
            return m;
        }).collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getTopCustomers(int year, int limit) {
        List<Object[]> rows = em.createNativeQuery(
            "SELECT c.customer_name, COUNT(b.bill_id), SUM(b.total_amount) " +
            "FROM bills b " +
            "JOIN customers c ON b.customer_id = c.customer_id " +
            "WHERE EXTRACT(YEAR FROM b.bill_date) = :year " +
            "GROUP BY c.customer_id, c.customer_name " +
            "ORDER BY SUM(b.total_amount) DESC " +
            "LIMIT :lim"
        ).setParameter("year", year).setParameter("lim", limit).getResultList();

        return rows.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("customerName", r[0]);
            m.put("billCount",    ((Number) r[1]).longValue());
            m.put("totalSpend",   r[2]);
            return m;
        }).collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getPaymentMethods(int year) {
        List<Object[]> rows = em.createNativeQuery(
            "SELECT payment_method, COUNT(*), SUM(total_amount) " +
            "FROM bills WHERE EXTRACT(YEAR FROM bill_date) = :year " +
            "GROUP BY payment_method ORDER BY SUM(total_amount) DESC"
        ).setParameter("year", year).getResultList();

        return rows.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("method", r[0]);
            m.put("count",  ((Number) r[1]).longValue());
            m.put("amount", r[2]);
            return m;
        }).collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getPurchasesVsSales(int year) {
        List<Object[]> salesRows = em.createNativeQuery(
            "SELECT EXTRACT(MONTH FROM bill_date), SUM(total_amount) " +
            "FROM bills WHERE EXTRACT(YEAR FROM bill_date) = :year GROUP BY 1"
        ).setParameter("year", year).getResultList();

        List<Object[]> purchaseRows = em.createNativeQuery(
            "SELECT EXTRACT(MONTH FROM invoice_date), SUM(COALESCE(final_price, total_amount)) " +
            "FROM purchases WHERE EXTRACT(YEAR FROM invoice_date) = :year GROUP BY 1"
        ).setParameter("year", year).getResultList();

        Map<Integer, Object> salesMap = new HashMap<>();
        for (Object[] r : salesRows) salesMap.put(((Number) r[0]).intValue(), r[1]);

        Map<Integer, Object> purchMap = new HashMap<>();
        for (Object[] r : purchaseRows) purchMap.put(((Number) r[0]).intValue(), r[1]);

        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("month",     i);
            row.put("sales",     salesMap.getOrDefault(i, 0));
            row.put("purchases", purchMap.getOrDefault(i, 0));
            result.add(row);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getCustomerSales(String period) {
        LocalDate today = LocalDate.now();
        LocalDate from = switch (period) {
            case "today" -> today;
            case "week"  -> today.with(DayOfWeek.MONDAY);
            case "year"  -> today.withDayOfYear(1);
            default      -> today.withDayOfMonth(1);   // "month"
        };

        // Summary
        Object[] sum = (Object[]) em.createNativeQuery(
            "SELECT COUNT(DISTINCT b.customer_id), COUNT(b.bill_id), COALESCE(SUM(b.total_amount),0) " +
            "FROM bills b WHERE b.bill_date BETWEEN :from AND :to"
        ).setParameter("from", from).setParameter("to", today).getSingleResult();

        BigDecimal grandTotal = (BigDecimal) sum[2];

        // Customer rows
        List<Object[]> rows = em.createNativeQuery(
            "SELECT c.customer_name, c.phone, COUNT(b.bill_id), " +
            "       SUM(b.total_amount), AVG(b.total_amount), MAX(b.bill_date) " +
            "FROM bills b " +
            "JOIN customers c ON b.customer_id = c.customer_id " +
            "WHERE b.bill_date BETWEEN :from AND :to " +
            "GROUP BY c.customer_id, c.customer_name, c.phone " +
            "ORDER BY SUM(b.total_amount) DESC"
        ).setParameter("from", from).setParameter("to", today).getResultList();

        List<Map<String, Object>> customers = new ArrayList<>();
        int rank = 1;
        for (Object[] r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            BigDecimal spend = (BigDecimal) r[3];
            double share = (grandTotal != null && grandTotal.compareTo(BigDecimal.ZERO) > 0)
                ? spend.doubleValue() / grandTotal.doubleValue() * 100 : 0;
            m.put("rank",         rank++);
            m.put("customerName", r[0]);
            m.put("phone",        r[1]);
            m.put("billCount",    ((Number) r[2]).longValue());
            m.put("totalSales",   spend);
            m.put("avgBillValue", r[4]);
            m.put("lastPurchase", r[5] != null ? r[5].toString() : null);
            m.put("sharePercent", Math.round(share * 10.0) / 10.0);
            customers.add(m);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("period",   period);
        result.put("from",     from.toString());
        result.put("to",       today.toString());
        result.put("summary",  Map.of(
            "uniqueCustomers", ((Number) sum[0]).longValue(),
            "totalBills",      ((Number) sum[1]).longValue(),
            "totalSales",      grandTotal
        ));
        result.put("customers", customers);
        return result;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getUserSales(String period) {
        LocalDate today = LocalDate.now();
        LocalDate from = switch (period) {
            case "today" -> today;
            case "week"  -> today.with(DayOfWeek.MONDAY);
            case "year"  -> today.withDayOfYear(1);
            default      -> today.withDayOfMonth(1);
        };

        Object[] sum = (Object[]) em.createNativeQuery(
            "SELECT COUNT(DISTINCT b.sales_person_id), COUNT(b.bill_id), COALESCE(SUM(b.total_amount),0) " +
            "FROM bills b " +
            "WHERE b.bill_date BETWEEN :from AND :to AND b.sales_person_id IS NOT NULL"
        ).setParameter("from", from).setParameter("to", today).getSingleResult();

        BigDecimal grandTotal = (BigDecimal) sum[2];

        List<Object[]> rows = em.createNativeQuery(
            "SELECT COALESCE(b.sales_person_name, 'Unassigned'), b.sales_person_id, " +
            "       COUNT(b.bill_id), SUM(b.total_amount), AVG(b.total_amount), MAX(b.bill_date) " +
            "FROM bills b " +
            "WHERE b.bill_date BETWEEN :from AND :to AND b.sales_person_id IS NOT NULL " +
            "GROUP BY b.sales_person_id, b.sales_person_name " +
            "ORDER BY SUM(b.total_amount) DESC"
        ).setParameter("from", from).setParameter("to", today).getResultList();

        List<Map<String, Object>> users = new ArrayList<>();
        int rank = 1;
        for (Object[] r : rows) {
            BigDecimal sales = (BigDecimal) r[3];
            double share = (grandTotal != null && grandTotal.compareTo(BigDecimal.ZERO) > 0)
                ? sales.doubleValue() / grandTotal.doubleValue() * 100 : 0;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rank",         rank++);
            m.put("userName",     r[0]);
            m.put("userId",       r[1] != null ? ((Number) r[1]).longValue() : null);
            m.put("billCount",    ((Number) r[2]).longValue());
            m.put("totalSales",   sales);
            m.put("avgBillValue", r[4]);
            m.put("lastSale",     r[5] != null ? r[5].toString() : null);
            m.put("sharePercent", Math.round(share * 10.0) / 10.0);
            users.add(m);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("period",  period);
        result.put("from",    from.toString());
        result.put("to",      today.toString());
        result.put("summary", Map.of(
            "activeUsers", ((Number) sum[0]).longValue(),
            "totalBills",  ((Number) sum[1]).longValue(),
            "totalSales",  grandTotal
        ));
        result.put("users", users);
        return result;
    }

    public Map<String, Object> getChatAnalytics(String period) {
        String schema = TenantContext.getCurrentTenant();
        LocalDate today = LocalDate.now();
        LocalDate from = switch (period) {
            case "today" -> today;
            case "week"  -> today.with(DayOfWeek.MONDAY);
            case "year"  -> today.withDayOfYear(1);
            default      -> today.withDayOfMonth(1);
        };

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("period", period);
        result.put("from", from.toString());
        result.put("to",   today.toString());

        if (schema == null) return result;

        try (Connection conn = dataSource.getConnection()) {
            conn.createStatement().execute("SET search_path TO \"" + schema + "\", public");

            // Verify chat tables exist
            try (ResultSet check = conn.createStatement().executeQuery(
                    "SELECT EXISTS(SELECT 1 FROM information_schema.tables " +
                    "WHERE table_schema='" + schema + "' AND table_name='chat_messages')")) {
                if (!check.next() || !check.getBoolean(1)) {
                    result.put("overview", emptyOverview());
                    result.put("unansweredQueries", List.of());
                    result.put("recentQueries",     List.of());
                    result.put("topProducts",       List.of());
                    result.put("topCategories",     List.of());
                    result.put("imageSearches",     List.of());
                    return result;
                }
            }

            java.sql.Date fromSql = java.sql.Date.valueOf(from);

            // ── 1. Overview ──────────────────────────────────────────────────────
            Map<String, Object> ov = emptyOverview();
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT " +
                "  COUNT(DISTINCT cs.session_id)," +
                "  COUNT(*) FILTER (WHERE cm.role='user')," +
                "  COUNT(*) FILTER (WHERE cm.role='user' AND (cm.image_url IS NOT NULL OR cm.content ILIKE '%similar to this image%'))," +
                "  COUNT(*) FILTER (WHERE COALESCE(cs.channel,'web')='whatsapp')," +
                "  COUNT(*) FILTER (WHERE COALESCE(cs.channel,'web')='web') " +
                "FROM chat_messages cm " +
                "JOIN chat_sessions cs ON cs.session_id = cm.session_id " +
                "WHERE cm.created_at >= ?")) {
                ps.setDate(1, fromSql);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    ov.put("totalSessions",    rs.getLong(1));
                    ov.put("totalQueries",     rs.getLong(2));
                    ov.put("imageSearches",    rs.getLong(3));
                    ov.put("whatsappSessions", rs.getLong(4));
                    ov.put("webSessions",      rs.getLong(5));
                }
            } catch (Exception e) {
                System.err.println("[ChatAnalytics] Overview error: " + e.getMessage());
            }

            // Count unanswered separately so it shows in overview card
            try (PreparedStatement ps = conn.prepareStatement(
                "WITH pairs AS (" +
                "  SELECT role, content, products_shown, created_at, session_id," +
                "    LEAD(products_shown) OVER (PARTITION BY session_id ORDER BY created_at) AS next_products," +
                "    LEAD(role)           OVER (PARTITION BY session_id ORDER BY created_at) AS next_role " +
                "  FROM chat_messages WHERE created_at >= ?" +
                ") " +
                "SELECT COUNT(*) FROM pairs p " +
                "JOIN chat_sessions cs ON cs.session_id = p.session_id " +
                "WHERE p.role='user' AND p.next_role='assistant' " +
                "AND p.content NOT ILIKE '%similar to this image%' " +
                "AND (p.next_products IS NULL OR jsonb_array_length(p.next_products)=0)")) {
                ps.setDate(1, fromSql);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) ov.put("unansweredCount", rs.getLong(1));
            } catch (Exception e) {
                ov.put("unansweredCount", 0L);
            }
            result.put("overview", ov);

            // ── 2. What customers couldn't find (STOCK GAP OPPORTUNITY) ─────────
            List<Map<String, Object>> unanswered = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                "WITH pairs AS (" +
                "  SELECT role, content, created_at, session_id," +
                "    LEAD(products_shown) OVER (PARTITION BY session_id ORDER BY created_at) AS next_products," +
                "    LEAD(role)           OVER (PARTITION BY session_id ORDER BY created_at) AS next_role " +
                "  FROM chat_messages WHERE created_at >= ?" +
                ") " +
                "SELECT p.content, p.created_at, COALESCE(cs.channel,'web') " +
                "FROM pairs p JOIN chat_sessions cs ON cs.session_id = p.session_id " +
                "WHERE p.role='user' AND p.next_role='assistant' " +
                "AND p.content NOT ILIKE '%similar to this image%' " +
                "AND (p.next_products IS NULL OR jsonb_array_length(p.next_products)=0) " +
                "ORDER BY p.created_at DESC LIMIT 20")) {
                ps.setDate(1, fromSql);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("query",     rs.getString(1));
                    m.put("askedAt",   rs.getTimestamp(2) != null ? rs.getTimestamp(2).toLocalDateTime().toString() : "");
                    m.put("channel",   rs.getString(3));
                    unanswered.add(m);
                }
            } catch (Exception e) {
                System.err.println("[ChatAnalytics] Unanswered error: " + e.getMessage());
            }
            result.put("unansweredQueries", unanswered);

            // ── 3. Recent customer queries ───────────────────────────────────────
            List<Map<String, Object>> recent = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                "WITH pairs AS (" +
                "  SELECT role, content, created_at, session_id," +
                "    LEAD(products_shown) OVER (PARTITION BY session_id ORDER BY created_at) AS next_products " +
                "  FROM chat_messages WHERE created_at >= ?" +
                ") " +
                "SELECT p.content, p.created_at, COALESCE(cs.channel,'web')," +
                "  CASE WHEN p.next_products IS NOT NULL AND jsonb_array_length(p.next_products)>0 " +
                "       THEN jsonb_array_length(p.next_products) ELSE 0 END " +
                "FROM pairs p JOIN chat_sessions cs ON cs.session_id = p.session_id " +
                "WHERE p.role='user' AND p.content NOT ILIKE '%similar to this image%' " +
                "ORDER BY p.created_at DESC LIMIT 20")) {
                ps.setDate(1, fromSql);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("query",         rs.getString(1));
                    m.put("askedAt",       rs.getTimestamp(2) != null ? rs.getTimestamp(2).toLocalDateTime().toString() : "");
                    m.put("channel",       rs.getString(3));
                    m.put("productsFound", rs.getInt(4));
                    recent.add(m);
                }
            } catch (Exception e) {
                System.err.println("[ChatAnalytics] Recent queries error: " + e.getMessage());
            }
            result.put("recentQueries", recent);

            // ── 4. Most requested products + current stock ───────────────────────
            List<Map<String, Object>> topProducts = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT t.product_name, t.category_name, t.times_shown," +
                "  COALESCE(p.stock_quantity, -1) AS stock_qty " +
                "FROM (" +
                "  SELECT prod->>'productName' AS product_name," +
                "         prod->>'categoryName' AS category_name," +
                "         (prod->>'productId')::integer AS product_id," +
                "         COUNT(*) AS times_shown " +
                "  FROM chat_messages, jsonb_array_elements(products_shown) AS prod " +
                "  WHERE products_shown IS NOT NULL AND created_at >= ? " +
                "  GROUP BY 1,2,3 ORDER BY COUNT(*) DESC LIMIT 12" +
                ") t LEFT JOIN products p ON p.product_id = t.product_id " +
                "ORDER BY t.times_shown DESC")) {
                ps.setDate(1, fromSql);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("productName",  rs.getString(1));
                    m.put("categoryName", rs.getString(2));
                    m.put("timesShown",   rs.getInt(3));
                    double stock = rs.getDouble(4);
                    m.put("stockQty", stock == -1 ? null : stock);
                    topProducts.add(m);
                }
            } catch (Exception e) {
                System.err.println("[ChatAnalytics] Top products error: " + e.getMessage());
            }
            result.put("topProducts", topProducts);

            // ── 5. Top categories ─────────────────────────────────────────────────
            List<Map<String, Object>> topCats = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT prod->>'categoryName', COUNT(*) " +
                "FROM chat_messages, jsonb_array_elements(products_shown) AS prod " +
                "WHERE products_shown IS NOT NULL " +
                "AND (prod->>'categoryName') IS NOT NULL AND (prod->>'categoryName') != '' " +
                "AND created_at >= ? " +
                "GROUP BY 1 ORDER BY COUNT(*) DESC LIMIT 8")) {
                ps.setDate(1, fromSql);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("categoryName", rs.getString(1));
                    m.put("timesShown",   rs.getLong(2));
                    topCats.add(m);
                }
            } catch (Exception e) {
                System.err.println("[ChatAnalytics] Top cats error: " + e.getMessage());
            }
            result.put("topCategories", topCats);

            // ── 6. Image searches with uploaded thumbnails ───────────────────────
            List<Map<String, Object>> imageSearches = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                "WITH pairs AS (" +
                "  SELECT role, image_url, created_at, session_id," +
                "    LEAD(products_shown) OVER (PARTITION BY session_id ORDER BY created_at) AS next_products " +
                "  FROM chat_messages WHERE created_at >= ?" +
                ") " +
                "SELECT p.image_url, p.created_at, COALESCE(cs.channel,'web')," +
                "  CASE WHEN p.next_products IS NOT NULL AND jsonb_array_length(p.next_products)>0 " +
                "       THEN jsonb_array_length(p.next_products) ELSE 0 END," +
                "  p.next_products " +
                "FROM pairs p JOIN chat_sessions cs ON cs.session_id = p.session_id " +
                "WHERE p.role='user' AND p.image_url IS NOT NULL " +
                "ORDER BY p.created_at DESC LIMIT 20")) {
                ps.setDate(1, fromSql);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("imageUrl",      rs.getString(1));
                    m.put("askedAt",       rs.getTimestamp(2) != null ? rs.getTimestamp(2).toLocalDateTime().toString() : "");
                    m.put("channel",       rs.getString(3));
                    m.put("productsFound", rs.getInt(4));
                    // Include first 4 matched product names for display
                    List<String> names = new ArrayList<>();
                    String prods = rs.getString(5);
                    if (prods != null) {
                        try {
                            com.fasterxml.jackson.databind.JsonNode arr = new com.fasterxml.jackson.databind.ObjectMapper().readTree(prods);
                            for (int i = 0; i < Math.min(4, arr.size()); i++) {
                                String pn = arr.get(i).path("productName").asText();
                                if (!pn.isEmpty()) names.add(pn);
                            }
                        } catch (Exception ignored) {}
                    }
                    m.put("matchedProducts", names);
                    imageSearches.add(m);
                }
            } catch (Exception e) {
                System.err.println("[ChatAnalytics] Image searches error: " + e.getMessage());
            }
            result.put("imageSearches", imageSearches);

        } catch (Exception e) {
            System.err.println("[ChatAnalytics] DB error: " + e.getMessage());
            result.put("overview", emptyOverview());
            result.put("unansweredQueries", List.of());
            result.put("recentQueries",     List.of());
            result.put("topProducts",       List.of());
            result.put("topCategories",     List.of());
            result.put("imageSearches",     List.of());
        }

        return result;
    }

    private Map<String, Object> emptyOverview() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalSessions", 0L); m.put("totalQueries", 0L);
        m.put("imageSearches", 0L); m.put("unansweredCount", 0L);
        m.put("whatsappSessions", 0L); m.put("webSessions", 0L);
        return m;
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getLowStock() {
        List<Object[]> rows = em.createNativeQuery(
            "SELECT p.product_id, p.product_name, COALESCE(p.category,'N/A'), " +
            "       p.stock_quantity, p.min_stock_level " +
            "FROM products p " +
            "WHERE p.is_active = true AND p.stock_quantity <= p.min_stock_level " +
            "ORDER BY p.stock_quantity ASC"
        ).getResultList();

        return rows.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("productId",    r[0]);
            m.put("productName",  r[1]);
            m.put("category",     r[2]);
            m.put("stockQty",     r[3]);
            m.put("minStockLevel",r[4]);
            return m;
        }).collect(Collectors.toList());
    }
}

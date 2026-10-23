package com.example.mybill.boutique.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One day's business figures (IST calendar day) for the agent's daily AI report: WhatsApp/Instagram boutique
 * orders (created, paid, revenue, by design, from ads) and website orders (paid, revenue, top items).
 * Timestamps in these tables are server-local (UTC on Cloud Run) - the day is converted to that range.
 */
@Service
public class DailyFiguresService {

    @PersistenceContext private EntityManager em;

    @Transactional(readOnly = true)
    public Map<String, Object> figures(LocalDate day) {
        // [day 00:00 IST, next day 00:00 IST) as UTC timestamps
        String from = day.atStartOfDay().minusHours(5).minusMinutes(30).toString().replace('T', ' ');
        String to = day.plusDays(1).atStartOfDay().minusHours(5).minusMinutes(30).toString().replace('T', ' ');
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", day.toString());

        Object[] created = (Object[]) em.createNativeQuery("""
                SELECT COUNT(*), COALESCE(SUM(total), 0), COUNT(*) FILTER (WHERE source_type = 'AD')
                  FROM boutique_orders WHERE created_at >= CAST(?1 AS TIMESTAMP) AND created_at < CAST(?2 AS TIMESTAMP)""")
            .setParameter(1, from).setParameter(2, to).getSingleResult();
        Object[] paid = (Object[]) em.createNativeQuery("""
                SELECT COUNT(*), COALESCE(SUM(total), 0)
                  FROM boutique_orders WHERE paid_at >= CAST(?1 AS TIMESTAMP) AND paid_at < CAST(?2 AS TIMESTAMP)""")
            .setParameter(1, from).setParameter(2, to).getSingleResult();
        Map<String, Object> chat = new LinkedHashMap<>();
        chat.put("orders_created", num(created[0]));
        chat.put("orders_created_value", money(created[1]));
        chat.put("orders_created_from_ads", num(created[2]));
        chat.put("orders_paid", num(paid[0]));
        chat.put("revenue_paid", money(paid[1]));
        List<Map<String, Object>> byDesign = new ArrayList<>();
        for (Object r : em.createNativeQuery("""
                SELECT i.design_code, i.design_name, COUNT(*), COUNT(o.paid_at)
                  FROM boutique_orders o JOIN boutique_order_items i ON i.order_id = o.order_id
                 WHERE o.created_at >= CAST(?1 AS TIMESTAMP) AND o.created_at < CAST(?2 AS TIMESTAMP)
                 GROUP BY i.design_code, i.design_name ORDER BY COUNT(*) DESC LIMIT 20""")
                .setParameter(1, from).setParameter(2, to).getResultList()) {
            Object[] a = (Object[]) r;
            byDesign.add(Map.of("design", a[0] + " " + a[1], "orders", num(a[2]), "paid", num(a[3])));
        }
        chat.put("orders_by_design", byDesign);
        Map<String, Object> byType = new LinkedHashMap<>();
        for (Object r : em.createNativeQuery("""
                SELECT COALESCE(order_type, 'GARMENT'), COUNT(*), COUNT(paid_at), COALESCE(SUM(total) FILTER (WHERE paid_at IS NOT NULL), 0)
                  FROM boutique_orders WHERE created_at >= CAST(?1 AS TIMESTAMP) AND created_at < CAST(?2 AS TIMESTAMP)
                 GROUP BY 1""").setParameter(1, from).setParameter(2, to).getResultList()) {
            Object[] a = (Object[]) r;
            byType.put(String.valueOf(a[0]).toLowerCase(), Map.of("orders", num(a[1]), "paid", num(a[2]), "revenue_paid", money(a[3])));
        }
        chat.put("orders_by_type", byType);   // garment (stitched) / fabric / readymade / mixed
        Object dispatch = em.createNativeQuery(
            "SELECT COUNT(*) FROM boutique_orders WHERE status = 'PAID' AND COALESCE(order_type, 'GARMENT') <> 'GARMENT'").getSingleResult();
        chat.put("goods_waiting_dispatch_now", num(dispatch));
        out.put("whatsapp_orders", chat);

        Map<String, Object> web = new LinkedHashMap<>();
        if (tableExists("ecom_orders")) {
            Object[] w = (Object[]) em.createNativeQuery("""
                    SELECT COUNT(*), COALESCE(SUM(amount), 0) FROM ecom_orders
                     WHERE paid_at >= CAST(?1 AS TIMESTAMP) AND paid_at < CAST(?2 AS TIMESTAMP)""")
                .setParameter(1, from).setParameter(2, to).getSingleResult();
            Object pending = em.createNativeQuery("""
                    SELECT COUNT(*) FROM ecom_orders WHERE status = 'PENDING'
                       AND created_at >= CAST(?1 AS TIMESTAMP) AND created_at < CAST(?2 AS TIMESTAMP)""")
                .setParameter(1, from).setParameter(2, to).getSingleResult();
            web.put("orders_paid", num(w[0]));
            web.put("revenue_paid", money(w[1]));
            web.put("checkouts_started_not_paid", num(pending));
            List<Map<String, Object>> items = new ArrayList<>();
            for (Object r : em.createNativeQuery("""
                    SELECT i.product_name, SUM(i.quantity), MAX(i.unit), SUM(i.total_price)
                      FROM ecom_orders o JOIN ecom_order_items i ON i.order_id = o.order_id
                     WHERE o.paid_at >= CAST(?1 AS TIMESTAMP) AND o.paid_at < CAST(?2 AS TIMESTAMP)
                     GROUP BY i.product_name ORDER BY SUM(i.total_price) DESC LIMIT 15""")
                    .setParameter(1, from).setParameter(2, to).getResultList()) {
                Object[] a = (Object[]) r;
                items.add(Map.of("product", String.valueOf(a[0]), "quantity", money(a[1]),
                    "unit", String.valueOf(a[2]), "value", money(a[3])));
            }
            web.put("top_items", items);
        }
        out.put("website_orders", web);
        return out;
    }

    private boolean tableExists(String table) {
        Object n = em.createNativeQuery("SELECT COUNT(*) FROM information_schema.tables "
            + "WHERE table_schema = current_schema() AND table_name = ?1").setParameter(1, table).getSingleResult();
        return ((Number) n).intValue() > 0;
    }

    private static long num(Object o) {
        return o == null ? 0 : ((Number) o).longValue();
    }

    private static BigDecimal money(Object o) {
        return o == null ? BigDecimal.ZERO : new BigDecimal(o.toString()).setScale(2, java.math.RoundingMode.HALF_UP);
    }
}

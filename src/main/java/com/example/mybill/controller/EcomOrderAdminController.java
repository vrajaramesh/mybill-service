package com.example.mybill.controller;

import com.example.mybill.multitenancy.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Admin view of ecom orders — list, status update, notes.
 * Requires ADMIN or SALES JWT (TenantFilter sets schema).
 */
@RestController
@RequestMapping("/api/ecom-orders")
public class EcomOrderAdminController {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Statuses in progression order. */
    public static final List<String> VALID_STATUSES =
        List.of("PAID", "PROCESSING", "READY", "SHIPPED", "DELIVERED", "CANCELLED");

    @GetMapping
    public ResponseEntity<?> listOrders(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search) {

        String s = TenantContext.getCurrentTenant();
        if (s == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        StringBuilder sql = new StringBuilder(
            "SELECT o.order_id, o.razorpay_order_id, o.razorpay_payment_id, " +
            "       o.amount, o.status, o.admin_notes, " +
            "       o.delivery_name, o.delivery_phone, o.delivery_email, " +
            "       o.delivery_address, o.delivery_city, o.delivery_pincode, " +
            "       o.notes, o.created_at, o.paid_at, " +
            "       c.name AS customer_name, c.email AS customer_email, c.phone AS customer_phone " +
            "FROM \"" + s + "\".ecom_orders o " +
            "LEFT JOIN \"" + s + "\".ecom_customers c ON c.customer_id = o.customer_id " +
            "WHERE 1=1 ");

        List<Object> params = new ArrayList<>();

        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            sql.append("AND o.status = ? ");
            params.add(status.toUpperCase());
        } else {
            // By default exclude PENDING (not yet paid) orders
            sql.append("AND o.status != 'PENDING' ");
        }

        if (search != null && !search.isBlank()) {
            sql.append("AND (o.delivery_name ILIKE ? OR o.delivery_phone ILIKE ? OR c.name ILIKE ?) ");
            String q = "%" + search.trim() + "%";
            params.add(q); params.add(q); params.add(q);
        }

        sql.append("ORDER BY o.created_at DESC");

        List<Map<String, Object>> orders = jdbcTemplate.queryForList(sql.toString(), params.toArray());
        if (orders.isEmpty()) return ResponseEntity.ok(List.of());

        List<Integer> orderIds = orders.stream()
            .map(o -> ((Number) o.get("order_id")).intValue()).toList();

        String placeholders = String.join(",", Collections.nCopies(orderIds.size(), "?"));
        List<Map<String, Object>> items = jdbcTemplate.queryForList(
            "SELECT id, order_id, product_id, product_name, unit, size, quantity, unit_price, total_price " +
            "FROM \"" + s + "\".ecom_order_items WHERE order_id IN (" + placeholders + ")",
            orderIds.toArray());

        Map<Integer, List<Map<String, Object>>> itemsByOrder = new HashMap<>();
        for (Map<String, Object> item : items) {
            int oid = ((Number) item.get("order_id")).intValue();
            itemsByOrder.computeIfAbsent(oid, k -> new ArrayList<>()).add(item);
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> order : orders) {
            Map<String, Object> o = new LinkedHashMap<>(order);
            int oid = ((Number) o.get("order_id")).intValue();
            o.put("items", itemsByOrder.getOrDefault(oid, List.of()));
            result.add(o);
        }

        return ResponseEntity.ok(result);
    }

    @GetMapping("/count/new")
    public ResponseEntity<?> countNewOrders() {
        String s = TenantContext.getCurrentTenant();
        if (s == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM \"" + s + "\".ecom_orders WHERE status = 'PAID'", Integer.class);
        return ResponseEntity.ok(Map.of("count", count == null ? 0 : count));
    }

    @PutMapping("/{orderId}/status")
    public ResponseEntity<?> updateStatus(
            @PathVariable int orderId,
            @RequestBody Map<String, String> body) {

        String s = TenantContext.getCurrentTenant();
        if (s == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        String newStatus = body.get("status");
        if (newStatus == null || !VALID_STATUSES.contains(newStatus.toUpperCase()))
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid status"));

        String adminNotes = body.get("adminNotes");

        if (adminNotes != null) {
            jdbcTemplate.update(
                "UPDATE \"" + s + "\".ecom_orders SET status = ?, admin_notes = ? WHERE order_id = ?",
                newStatus.toUpperCase(), adminNotes, orderId);
        } else {
            jdbcTemplate.update(
                "UPDATE \"" + s + "\".ecom_orders SET status = ? WHERE order_id = ?",
                newStatus.toUpperCase(), orderId);
        }

        return ResponseEntity.ok(Map.of("updated", true, "status", newStatus.toUpperCase()));
    }
}

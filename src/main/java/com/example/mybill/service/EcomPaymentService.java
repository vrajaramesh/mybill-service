package com.example.mybill.service;

import com.example.mybill.boutique.payment.AgentEventNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Website (ecom) order payments. An order is marked PAID once - by the checkout's verify call or by Razorpay's
 * order.paid webhook, whichever comes first - and then the staff get a WhatsApp alert (via srisa-agent) to pack
 * and dispatch it.
 */
@Service
public class EcomPaymentService {

    private static final Logger log = LoggerFactory.getLogger(EcomPaymentService.class);

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private AgentEventNotifier agentNotifier;

    /**
     * PENDING -> PAID for this Razorpay order (and customer, when given). Returns the internal order id when this
     * call made the change, else null (unknown, already paid, or another customer's order).
     */
    public Integer markPaid(String schema, String razorpayOrderId, String razorpayPaymentId, Integer customerId) {
        List<Integer> ids = jdbcTemplate.queryForList(
            "UPDATE \"" + schema + "\".ecom_orders SET status = 'PAID', razorpay_payment_id = ?, paid_at = NOW() " +
            "WHERE razorpay_order_id = ? AND status = 'PENDING'" + (customerId != null ? " AND customer_id = ?" : "") +
            " RETURNING order_id",
            Integer.class, customerId != null
                ? new Object[]{razorpayPaymentId, razorpayOrderId, customerId}
                : new Object[]{razorpayPaymentId, razorpayOrderId});
        if (ids.isEmpty()) return null;
        int orderId = ids.get(0);
        jdbcTemplate.update("DELETE FROM \"" + schema + "\".cart_items WHERE customer_id = " +
            "(SELECT customer_id FROM \"" + schema + "\".ecom_orders WHERE order_id = ?)", orderId);
        log.info("ECOM_ORDER_PAID schema={} order={} payment={}", schema, orderId, razorpayPaymentId);
        CompletableFuture.runAsync(() -> alertStaff(schema, orderId));
        return orderId;
    }

    /** Amount (paise) of a website order, or null if unknown - to check webhook amounts. */
    public Long amountPaise(String schema, String razorpayOrderId) {
        List<BigDecimal> rows = jdbcTemplate.queryForList(
            "SELECT amount FROM \"" + schema + "\".ecom_orders WHERE razorpay_order_id = ?", BigDecimal.class, razorpayOrderId);
        return rows.isEmpty() || rows.get(0) == null ? null : rows.get(0).movePointRight(2).longValue();
    }

    private void alertStaff(String schema, int orderId) {
        try {
            Map<String, Object> o = jdbcTemplate.queryForMap(
                "SELECT order_id, amount, delivery_name, delivery_phone, delivery_address, delivery_city, delivery_pincode " +
                "FROM \"" + schema + "\".ecom_orders WHERE order_id = ?", orderId);
            List<Map<String, Object>> items = jdbcTemplate.queryForList(
                "SELECT product_name, quantity, unit, size FROM \"" + schema + "\".ecom_order_items WHERE order_id = ? ORDER BY id",
                orderId);
            String itemText = items.stream().map(i -> {
                String qty = i.get("quantity") == null ? "" : new BigDecimal(i.get("quantity").toString()).stripTrailingZeros().toPlainString();
                String unit = i.get("unit") == null ? "" : " " + i.get("unit");
                String size = i.get("size") == null || i.get("size").toString().isBlank() ? "" : " (" + i.get("size") + ")";
                return i.get("product_name") + size + " × " + qty + unit;
            }).collect(Collectors.joining(", "));
            String address = String.join(", ", java.util.stream.Stream.of(o.get("delivery_address"), o.get("delivery_city"),
                o.get("delivery_pincode")).filter(v -> v != null && !v.toString().isBlank()).map(Object::toString).toList());

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("event", "ecom.order.paid");
            body.put("event_id", "ecom-paid:" + schema + ":" + orderId);
            body.put("order_no", "#" + orderId);
            body.put("customer_name", o.get("delivery_name"));
            body.put("customer_phone", o.get("delivery_phone"));
            body.put("amount", o.get("amount"));
            body.put("items", itemText.length() > 600 ? itemText.substring(0, 597) + "..." : itemText);
            body.put("delivery_address", address);
            if (!agentNotifier.post(body, "website order #" + orderId)) {
                log.error("ECOM_STAFF_ALERT_FAILED schema={} order={} - check Online Orders", schema, orderId);
            }
        } catch (Exception e) {
            log.error("ECOM_STAFF_ALERT_FAILED schema={} order={}: {}", schema, orderId, e.toString());
        }
    }
}

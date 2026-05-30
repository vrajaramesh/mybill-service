package com.example.mybill.controller;

import com.example.mybill.dto.Firm;
import com.example.mybill.multitenancy.JwtUtil;
import com.example.mybill.repository.FirmRepository;
import com.razorpay.Order;
import com.razorpay.RazorpayClient;
import io.jsonwebtoken.Claims;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping("/api/public")
public class RazorpayController {

    @Value("${razorpay.key_id}")
    private String keyId;

    @Value("${razorpay.key_secret}")
    private String keySecret;

    @Autowired private FirmRepository firmRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JwtUtil jwtUtil;

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Optional<Firm> getActiveFirm(String firmCode) {
        return firmRepository.findByFirmCode(firmCode.toLowerCase().trim())
            .filter(f -> Boolean.TRUE.equals(f.getIsActive()));
    }

    /**
     * Validates the ecom customer JWT in the Authorization header.
     * Returns the customer row from ecom_customers, or null if invalid.
     */
    private Map<String, Object> extractEcomCustomer(String firmCode, String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return null;
        String token = authHeader.substring(7);
        if (!jwtUtil.isValid(token)) return null;
        Claims claims = jwtUtil.extractClaims(token);
        if (!"ECOM_CUSTOMER".equals(claims.get("role", String.class))) return null;

        Optional<Firm> firmOpt = getActiveFirm(firmCode);
        if (firmOpt.isEmpty()) return null;
        String s = firmOpt.get().getSchemaName();

        String email = claims.getSubject();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT customer_id, name, email, phone FROM \"" + s + "\".ecom_customers WHERE email = ? AND is_active = TRUE",
            email);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ── Expose Razorpay key_id to frontend (public) ───────────────────────────

    @GetMapping("/{firmCode}/payment/config")
    public ResponseEntity<?> getPaymentConfig(@PathVariable String firmCode) {
        if (getActiveFirm(firmCode).isEmpty())
            return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of("keyId", keyId));
    }

    // ── Step 1: Create Razorpay order ─────────────────────────────────────────

    /**
     * POST /api/public/{firmCode}/checkout/create-order
     *
     * Body: {
     *   deliveryName, deliveryPhone, deliveryEmail,
     *   deliveryAddress, deliveryCity, deliveryPincode,
     *   notes (optional)
     * }
     *
     * Returns: { razorpayOrderId, amount (INR), currency, orderId (internal) }
     */
    @PostMapping("/{firmCode}/checkout/create-order")
    public ResponseEntity<?> createOrder(
            @PathVariable String firmCode,
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        Map<String, Object> customer = extractEcomCustomer(firmCode, authHeader);
        if (customer == null)
            return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));

        Optional<Firm> firmOpt = getActiveFirm(firmCode);
        if (firmOpt.isEmpty()) return ResponseEntity.notFound().build();
        String s = firmOpt.get().getSchemaName();
        int customerId = ((Number) customer.get("customer_id")).intValue();

        // Validate required delivery fields
        String deliveryName  = body.getOrDefault("deliveryName", "").trim();
        String deliveryPhone = body.getOrDefault("deliveryPhone", "").trim();
        String deliveryEmail = body.getOrDefault("deliveryEmail", "").trim();
        String deliveryAddr  = body.getOrDefault("deliveryAddress", "").trim();
        String deliveryCity  = body.getOrDefault("deliveryCity", "").trim();
        String deliveryPin   = body.getOrDefault("deliveryPincode", "").trim();
        String notes         = body.getOrDefault("notes", "");

        if (deliveryName.isEmpty() || deliveryPhone.isEmpty() || deliveryAddr.isEmpty()
                || deliveryCity.isEmpty() || deliveryPin.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "All delivery fields are required"));
        }

        // Fetch cart items with product details
        String cartSql =
            "SELECT ci.item_id, ci.product_id, ci.quantity, ci.size, " +
            "       p.product_name, p.selling_price, p.unit " +
            "FROM \"" + s + "\".cart_items ci " +
            "JOIN \"" + s + "\".products p ON p.product_id = ci.product_id " +
            "WHERE ci.customer_id = ?";
        List<Map<String, Object>> cartRows = jdbcTemplate.queryForList(cartSql, customerId);

        if (cartRows.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "Cart is empty"));

        // Calculate total in INR
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> row : cartRows) {
            BigDecimal price = (BigDecimal) row.get("selling_price");
            BigDecimal qty   = (BigDecimal) row.get("quantity");
            total = total.add(price.multiply(qty));
        }
        total = total.setScale(2, RoundingMode.HALF_UP);

        // Create Razorpay order
        try {
            RazorpayClient razorpay = new RazorpayClient(keyId, keySecret);

            JSONObject orderRequest = new JSONObject();
            orderRequest.put("amount", total.multiply(new BigDecimal("100")).intValue()); // paise
            orderRequest.put("currency", "INR");
            orderRequest.put("receipt", "rcpt_" + customerId + "_" + System.currentTimeMillis());
            orderRequest.put("payment_capture", 1);

            Order razorpayOrder = razorpay.orders.create(orderRequest);
            String razorpayOrderId = razorpayOrder.get("id");

            // Save pending order in DB
            jdbcTemplate.update(
                "INSERT INTO \"" + s + "\".ecom_orders " +
                "(customer_id, razorpay_order_id, amount, status, delivery_name, delivery_phone, " +
                " delivery_email, delivery_address, delivery_city, delivery_pincode, notes) " +
                "VALUES (?,?,?,'PENDING',?,?,?,?,?,?,?)",
                customerId, razorpayOrderId, total,
                deliveryName, deliveryPhone, deliveryEmail,
                deliveryAddr, deliveryCity, deliveryPin, notes);

            // Get the internal order_id
            Integer internalOrderId = jdbcTemplate.queryForObject(
                "SELECT order_id FROM \"" + s + "\".ecom_orders WHERE razorpay_order_id = ?",
                Integer.class, razorpayOrderId);

            // Save order items
            for (Map<String, Object> row : cartRows) {
                BigDecimal price = (BigDecimal) row.get("selling_price");
                BigDecimal qty   = (BigDecimal) row.get("quantity");
                jdbcTemplate.update(
                    "INSERT INTO \"" + s + "\".ecom_order_items " +
                    "(order_id, product_id, product_name, unit, size, quantity, unit_price, total_price) " +
                    "VALUES (?,?,?,?,?,?,?,?)",
                    internalOrderId,
                    ((Number) row.get("product_id")).intValue(),
                    row.get("product_name"),
                    row.get("unit"),
                    row.get("size"),
                    qty, price,
                    price.multiply(qty).setScale(2, RoundingMode.HALF_UP));
            }

            return ResponseEntity.ok(Map.of(
                "razorpayOrderId", razorpayOrderId,
                "amount",          total,
                "amountPaise",     total.multiply(new BigDecimal("100")).intValue(),
                "currency",        "INR",
                "orderId",         internalOrderId,
                "customerName",    customer.get("name"),
                "customerEmail",   deliveryEmail,
                "customerPhone",   deliveryPhone
            ));

        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "Payment gateway error: " + e.getMessage()));
        }
    }

    // ── Step 2: Verify payment signature ─────────────────────────────────────

    /**
     * POST /api/public/{firmCode}/checkout/verify
     *
     * Body: {
     *   razorpayOrderId,
     *   razorpayPaymentId,
     *   razorpaySignature
     * }
     *
     * Returns: { success: true, orderId } or 400 error
     */
    @PostMapping("/{firmCode}/checkout/verify")
    public ResponseEntity<?> verifyPayment(
            @PathVariable String firmCode,
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        Map<String, Object> customer = extractEcomCustomer(firmCode, authHeader);
        if (customer == null)
            return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));

        Optional<Firm> firmOpt = getActiveFirm(firmCode);
        if (firmOpt.isEmpty()) return ResponseEntity.notFound().build();
        String s = firmOpt.get().getSchemaName();
        int customerId = ((Number) customer.get("customer_id")).intValue();

        String razorpayOrderId   = body.get("razorpayOrderId");
        String razorpayPaymentId = body.get("razorpayPaymentId");
        String razorpaySignature = body.get("razorpaySignature");

        if (razorpayOrderId == null || razorpayPaymentId == null || razorpaySignature == null)
            return ResponseEntity.badRequest().body(Map.of("error", "Missing payment details"));

        // Verify HMAC-SHA256 signature
        try {
            String payload = razorpayOrderId + "|" + razorpayPaymentId;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(keySecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexSig = new StringBuilder();
            for (byte b : hash) hexSig.append(String.format("%02x", b));

            if (!hexSig.toString().equals(razorpaySignature))
                return ResponseEntity.badRequest().body(Map.of("error", "Payment verification failed"));

        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "Signature verification error"));
        }

        // Mark order as PAID
        int updated = jdbcTemplate.update(
            "UPDATE \"" + s + "\".ecom_orders SET status = 'PAID', razorpay_payment_id = ?, paid_at = NOW() " +
            "WHERE razorpay_order_id = ? AND customer_id = ? AND status = 'PENDING'",
            razorpayPaymentId, razorpayOrderId, customerId);

        if (updated == 0)
            return ResponseEntity.badRequest().body(Map.of("error", "Order not found or already processed"));

        // Get internal order_id
        Integer orderId = jdbcTemplate.queryForObject(
            "SELECT order_id FROM \"" + s + "\".ecom_orders WHERE razorpay_order_id = ?",
            Integer.class, razorpayOrderId);

        // Clear the customer's cart
        jdbcTemplate.update(
            "DELETE FROM \"" + s + "\".cart_items WHERE customer_id = ?", customerId);

        return ResponseEntity.ok(Map.of("success", true, "orderId", orderId));
    }

    // ── Order history ─────────────────────────────────────────────────────────

    /**
     * GET /api/public/{firmCode}/orders
     * Returns the logged-in customer's past orders (newest first).
     */
    @GetMapping("/{firmCode}/orders")
    public ResponseEntity<?> getOrders(
            @PathVariable String firmCode,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        Map<String, Object> customer = extractEcomCustomer(firmCode, authHeader);
        if (customer == null)
            return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));

        Optional<Firm> firmOpt = getActiveFirm(firmCode);
        if (firmOpt.isEmpty()) return ResponseEntity.notFound().build();
        String s = firmOpt.get().getSchemaName();
        int customerId = ((Number) customer.get("customer_id")).intValue();

        // Orders
        List<Map<String, Object>> orders = jdbcTemplate.queryForList(
            "SELECT order_id, razorpay_order_id, razorpay_payment_id, amount, status, " +
            "       delivery_name, delivery_phone, delivery_address, delivery_city, delivery_pincode, " +
            "       notes, created_at, paid_at " +
            "FROM \"" + s + "\".ecom_orders WHERE customer_id = ? ORDER BY created_at DESC",
            customerId);

        if (orders.isEmpty()) return ResponseEntity.ok(List.of());

        // Items for each order
        List<Integer> orderIds = orders.stream()
            .map(o -> ((Number) o.get("order_id")).intValue())
            .toList();

        String placeholders = String.join(",", Collections.nCopies(orderIds.size(), "?"));
        List<Map<String, Object>> items = jdbcTemplate.queryForList(
            "SELECT id, order_id, product_id, product_name, unit, size, quantity, unit_price, total_price " +
            "FROM \"" + s + "\".ecom_order_items WHERE order_id IN (" + placeholders + ")",
            orderIds.toArray());

        // Group items by order_id
        Map<Integer, List<Map<String, Object>>> itemsByOrder = new HashMap<>();
        for (Map<String, Object> item : items) {
            int oid = ((Number) item.get("order_id")).intValue();
            itemsByOrder.computeIfAbsent(oid, k -> new ArrayList<>()).add(item);
        }

        // Attach items to orders
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> order : orders) {
            Map<String, Object> o = new LinkedHashMap<>(order);
            int oid = ((Number) o.get("order_id")).intValue();
            o.put("items", itemsByOrder.getOrDefault(oid, List.of()));
            result.add(o);
        }

        return ResponseEntity.ok(result);
    }

    /**
     * GET /api/public/{firmCode}/orders/{orderId}
     * Returns a single order's full details.
     */
    @GetMapping("/{firmCode}/orders/{orderId}")
    public ResponseEntity<?> getOrder(
            @PathVariable String firmCode,
            @PathVariable int orderId,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        Map<String, Object> customer = extractEcomCustomer(firmCode, authHeader);
        if (customer == null)
            return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));

        Optional<Firm> firmOpt = getActiveFirm(firmCode);
        if (firmOpt.isEmpty()) return ResponseEntity.notFound().build();
        String s = firmOpt.get().getSchemaName();
        int customerId = ((Number) customer.get("customer_id")).intValue();

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT * FROM \"" + s + "\".ecom_orders WHERE order_id = ? AND customer_id = ?",
            orderId, customerId);
        if (rows.isEmpty()) return ResponseEntity.notFound().build();

        Map<String, Object> order = new LinkedHashMap<>(rows.get(0));
        List<Map<String, Object>> items = jdbcTemplate.queryForList(
            "SELECT * FROM \"" + s + "\".ecom_order_items WHERE order_id = ?", orderId);
        order.put("items", items);

        return ResponseEntity.ok(order);
    }
}

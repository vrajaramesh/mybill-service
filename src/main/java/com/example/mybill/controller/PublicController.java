package com.example.mybill.controller;

import com.example.mybill.dto.Firm;
import com.example.mybill.multitenancy.JwtUtil;
import com.example.mybill.repository.FirmRepository;
import io.jsonwebtoken.Claims;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Public (no-auth) endpoints for the e-commerce storefront.
 * All data is scoped to the requested firmCode.
 */
@RestController
@RequestMapping("/api/public")
public class PublicController {

    @Autowired
    private FirmRepository firmRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtUtil jwtUtil;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /** Returns true if the given table exists in the given schema. */
    private boolean tableExists(String schema, String table) {
        String sql = "SELECT EXISTS (" +
                     "  SELECT 1 FROM information_schema.tables " +
                     "  WHERE table_schema = ? AND table_name = ?" +
                     ")";
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(sql, Boolean.class, schema, table));
    }

    /** Basic firm info for the storefront header, including whatsappPhone from firm_settings. */
    @GetMapping("/{firmCode}/info")
    public ResponseEntity<?> getFirmInfo(@PathVariable String firmCode) {
        return firmRepository.findByFirmCode(firmCode.toLowerCase().trim())
            .filter(f -> Boolean.TRUE.equals(f.getIsActive()))
            .map(f -> {
                String s = f.getSchemaName();
                String whatsappPhone = null;
                if (tableExists(s, "firm_settings")) {
                    List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                        "SELECT value FROM \"" + s + "\".firm_settings WHERE key = 'whatsappPhone'");
                    if (!rows.isEmpty() && rows.get(0).get("value") != null) {
                        whatsappPhone = (String) rows.get(0).get("value");
                    }
                }
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("firmName", f.getFirmName());
                result.put("firmCode", f.getFirmCode());
                if (whatsappPhone != null) result.put("whatsappPhone", whatsappPhone);
                return ResponseEntity.ok(result);
            })
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Online categories that have at least one active, online product.
     */
    @GetMapping("/{firmCode}/categories")
    public ResponseEntity<?> getCategories(@PathVariable String firmCode) {
        Optional<Firm> firmOpt = firmRepository.findByFirmCode(firmCode.toLowerCase().trim());
        if (firmOpt.isEmpty() || !Boolean.TRUE.equals(firmOpt.get().getIsActive())) {
            return ResponseEntity.notFound().build();
        }

        String s = firmOpt.get().getSchemaName();
        boolean hasIsOnline = columnExists(s, "products", "is_online");

        String isOnlineFilter = hasIsOnline ? " AND p.is_online = TRUE" : "";
        String sql = "SELECT pc.category_name " +
                     "FROM \"" + s + "\".product_category pc " +
                     "WHERE pc.is_online = TRUE " +
                     "  AND EXISTS (" +
                     "    SELECT 1 FROM \"" + s + "\".products p " +
                     "    WHERE p.category = pc.category_name AND p.is_active = TRUE" + isOnlineFilter +
                     "  ) " +
                     "ORDER BY pc.category_name";

        List<String> categories = jdbcTemplate.queryForList(sql, String.class);
        return ResponseEntity.ok(categories);
    }

    /**
     * Sub-categories for a given category.
     * Returns empty list if product_sub_category table does not exist yet.
     */
    @GetMapping("/{firmCode}/subcategories")
    public ResponseEntity<?> getSubCategories(
            @PathVariable String firmCode,
            @RequestParam(required = false) String category) {

        Optional<Firm> firmOpt = firmRepository.findByFirmCode(firmCode.toLowerCase().trim());
        if (firmOpt.isEmpty() || !Boolean.TRUE.equals(firmOpt.get().getIsActive())) {
            return ResponseEntity.notFound().build();
        }

        String s = firmOpt.get().getSchemaName();

        if (!tableExists(s, "product_sub_category")) {
            return ResponseEntity.ok(List.of());
        }

        String sql = "SELECT psc.sub_cat_name " +
                     "FROM \"" + s + "\".product_sub_category psc " +
                     "WHERE psc.is_online = TRUE";

        List<Object> params = new ArrayList<>();
        if (category != null && !category.isBlank()) {
            sql += " AND psc.category_name = ?";
            params.add(category);
        }
        sql += " ORDER BY psc.sub_cat_name";

        List<String> subCats = jdbcTemplate.queryForList(sql, String.class, params.toArray());
        return ResponseEntity.ok(subCats);
    }

    /**
     * Active, online products for a firm, optionally filtered by category.
     * Gracefully handles schemas where subcategory migration has not been applied.
     */
    @GetMapping("/{firmCode}/products")
    public ResponseEntity<?> getProducts(
            @PathVariable String firmCode,
            @RequestParam(required = false) String category,
            @RequestParam(required = false, defaultValue = "false") boolean newArrivals) {

        Optional<Firm> firmOpt = firmRepository.findByFirmCode(firmCode.toLowerCase().trim());
        if (firmOpt.isEmpty() || !Boolean.TRUE.equals(firmOpt.get().getIsActive())) {
            return ResponseEntity.notFound().build();
        }

        String s = firmOpt.get().getSchemaName();
        boolean hasIsOnline      = columnExists(s, "products", "is_online");
        boolean hasSizes         = columnExists(s, "products", "sizes");
        boolean hasSubCatCol     = columnExists(s, "products", "sub_category_id");
        boolean hasSubCatMap     = tableExists(s, "product_sub_cat_map");
        boolean hasSubCategory   = tableExists(s, "product_sub_category");

        String sizesSelect   = hasSizes     ? ", p.sizes"                                               : "";
        String subCatJoin    = (hasSubCatCol && hasSubCategory)
            ? " LEFT JOIN \"" + s + "\".product_sub_category psc_d ON psc_d.id = p.sub_category_id" : "";
        String subCatSelect  = (hasSubCatCol && hasSubCategory) ? ", psc_d.sub_cat_name AS _sub_cat" : "";

        StringBuilder sql = new StringBuilder(
            "SELECT p.product_id, p.product_name, p.description, p.category, " +
            "       p.unit, p.selling_price, p.stock_quantity, p.suitable_for, p.tags" +
            sizesSelect + subCatSelect + ", " +
            "       p.created_at, pi.image_url, pi.image_id " +
            "FROM \"" + s + "\".products p " +
            "LEFT JOIN \"" + s + "\".product_images pi ON pi.product_id = p.product_id " +
            "  AND (pi.media_type IS NULL OR pi.media_type = 'image') " +
            "LEFT JOIN \"" + s + "\".product_category pc ON pc.category_name = p.category " +
            subCatJoin +
            " WHERE p.is_active = TRUE " +
            "  AND (pc.is_online = TRUE OR p.category IS NULL) "
        );
        final boolean useDirectSubCat = hasSubCatCol && hasSubCategory;

        if (hasIsOnline) {
            sql.append("  AND p.is_online = TRUE ");
        }

        if (hasSubCatMap && hasSubCategory) {
            sql.append(
                "  AND (" +
                "    NOT EXISTS (SELECT 1 FROM \"" + s + "\".product_sub_cat_map m WHERE m.product_id = p.product_id)" +
                "    OR EXISTS (" +
                "      SELECT 1 FROM \"" + s + "\".product_sub_cat_map m" +
                "      JOIN \"" + s + "\".product_sub_category psc ON psc.id = m.sub_category_id" +
                "      WHERE m.product_id = p.product_id AND psc.is_online = TRUE" +
                "    )" +
                "  ) "
            );
        }

        List<Object> params = new ArrayList<>();
        if (category != null && !category.isBlank()) {
            sql.append("AND p.category = ? ");
            params.add(category);
        }
        if (newArrivals) {
            sql.append("AND p.created_at >= NOW() - INTERVAL '30 days' ");
        }

        sql.append("ORDER BY p.created_at DESC NULLS LAST, pi.image_id ASC");

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), params.toArray());

        // Group rows by product_id, collecting image_url into a list
        Map<Integer, Map<String, Object>> productMap = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Integer pid = ((Number) row.get("product_id")).intValue();
            productMap.computeIfAbsent(pid, k -> {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("productId",      k);
                p.put("productName",    row.get("product_name"));
                p.put("description",    row.get("description"));
                p.put("category",       row.get("category"));
                p.put("unit",           row.get("unit"));
                p.put("sellingPrice",   row.get("selling_price"));
                p.put("stockQuantity",  row.get("stock_quantity"));
                p.put("suitableFor",    row.get("suitable_for"));
                p.put("tags",           row.get("tags"));
                p.put("sizes",          hasSizes ? row.get("sizes") : null);
                p.put("createdAt",      row.get("created_at"));
                p.put("images",         new ArrayList<String>());
                // Seed subCategories with the direct sub_category_id value if present
                List<String> subCats = new ArrayList<>();
                if (useDirectSubCat) {
                    String direct = (String) row.get("_sub_cat");
                    if (direct != null && !direct.isBlank()) subCats.add(direct);
                }
                p.put("subCategories",  subCats);
                return p;
            });

            String imgUrl = (String) row.get("image_url");
            if (imgUrl != null && !imgUrl.isBlank()) {
                //noinspection unchecked
                ((List<String>) productMap.get(pid).get("images")).add(imgUrl);
            }
        }

        // Fetch sub-categories only if migration tables exist
        if (hasSubCatMap && hasSubCategory && !productMap.isEmpty()) {
            String placeholders = productMap.keySet().stream()
                .map(i -> "?").collect(java.util.stream.Collectors.joining(","));
            String subCatSql =
                "SELECT m.product_id, psc.sub_cat_name " +
                "FROM \"" + s + "\".product_sub_cat_map m " +
                "JOIN \"" + s + "\".product_sub_category psc ON psc.id = m.sub_category_id " +
                "WHERE psc.is_online = TRUE AND m.product_id IN (" + placeholders + ")";
            List<Map<String, Object>> scRows = jdbcTemplate.queryForList(
                subCatSql, productMap.keySet().toArray());
            for (Map<String, Object> scRow : scRows) {
                Integer pid = ((Number) scRow.get("product_id")).intValue();
                String scName = (String) scRow.get("sub_cat_name");
                if (scName != null && productMap.containsKey(pid)) {
                    //noinspection unchecked
                    ((List<String>) productMap.get(pid).get("subCategories")).add(scName);
                }
            }
        }

        return ResponseEntity.ok(new ArrayList<>(productMap.values()));
    }

    // ── Ecom Customer Auth ────────────────────────────────────────────────────

    @PostMapping("/{firmCode}/auth/register")
    public ResponseEntity<?> register(
            @PathVariable String firmCode,
            @RequestBody Map<String, String> body) {

        Optional<Firm> firmOpt = firmRepository.findByFirmCode(firmCode.toLowerCase().trim());
        if (firmOpt.isEmpty() || !Boolean.TRUE.equals(firmOpt.get().getIsActive())) {
            return ResponseEntity.notFound().build();
        }

        String s = firmOpt.get().getSchemaName();
        String name = body.get("name");
        String email = body.get("email");
        String password = body.get("password");
        String phone = body.get("phone");

        if (name == null || name.isBlank() || email == null || email.isBlank()
                || password == null || password.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "name, email and password are required"));
        }

        // Check email uniqueness
        List<Map<String, Object>> existing = jdbcTemplate.queryForList(
            "SELECT customer_id FROM \"" + s + "\".ecom_customers WHERE email = ?", email.toLowerCase().trim());
        if (!existing.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Email already registered"));
        }

        String hash = passwordEncoder.encode(password);
        jdbcTemplate.update(
            "INSERT INTO \"" + s + "\".ecom_customers (name, email, phone, password_hash) VALUES (?, ?, ?, ?)",
            name.trim(), email.toLowerCase().trim(), phone, hash);

        List<Map<String, Object>> newCustomer = jdbcTemplate.queryForList(
            "SELECT customer_id, name, email FROM \"" + s + "\".ecom_customers WHERE email = ?",
            email.toLowerCase().trim());

        if (newCustomer.isEmpty()) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Registration failed"));
        }

        Map<String, Object> cust = newCustomer.get(0);
        Long customerId = ((Number) cust.get("customer_id")).longValue();
        String token = jwtUtil.generateToken(email.toLowerCase().trim(), s,
            firmOpt.get().getFirmId(), "ECOM_CUSTOMER");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", token);
        result.put("customerId", customerId);
        result.put("name", cust.get("name"));
        result.put("email", cust.get("email"));
        result.put("firmCode", firmOpt.get().getFirmCode());
        return ResponseEntity.ok(result);
    }

    @PostMapping("/{firmCode}/auth/login")
    public ResponseEntity<?> login(
            @PathVariable String firmCode,
            @RequestBody Map<String, String> body) {

        Optional<Firm> firmOpt = firmRepository.findByFirmCode(firmCode.toLowerCase().trim());
        if (firmOpt.isEmpty() || !Boolean.TRUE.equals(firmOpt.get().getIsActive())) {
            return ResponseEntity.notFound().build();
        }

        String s = firmOpt.get().getSchemaName();
        String email = body.get("email");
        String password = body.get("password");

        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "email and password are required"));
        }

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT customer_id, name, email, password_hash, is_active FROM \"" + s + "\".ecom_customers WHERE email = ?",
            email.toLowerCase().trim());

        if (rows.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid email or password"));
        }

        Map<String, Object> cust = rows.get(0);
        Boolean isActive = (Boolean) cust.get("is_active");
        if (Boolean.FALSE.equals(isActive)) {
            return ResponseEntity.status(401).body(Map.of("error", "Account is inactive"));
        }

        String hash = (String) cust.get("password_hash");
        if (!passwordEncoder.matches(password, hash)) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid email or password"));
        }

        Long customerId = ((Number) cust.get("customer_id")).longValue();
        String token = jwtUtil.generateToken(email.toLowerCase().trim(), s,
            firmOpt.get().getFirmId(), "ECOM_CUSTOMER");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", token);
        result.put("customerId", customerId);
        result.put("name", cust.get("name"));
        result.put("email", cust.get("email"));
        result.put("firmCode", firmOpt.get().getFirmCode());
        return ResponseEntity.ok(result);
    }

    // ── Ecom Cart ─────────────────────────────────────────────────────────────

    /**
     * Validates the ecom JWT and returns the customer row, or null if invalid.
     */
    private Map<String, Object> extractEcomCustomer(String schemaName, String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return null;
        String token = authHeader.substring(7);
        if (!jwtUtil.isValid(token)) return null;
        try {
            Claims claims = jwtUtil.extractClaims(token);
            String role = claims.get("role", String.class);
            if (!"ECOM_CUSTOMER".equals(role)) return null;
            String email = claims.getSubject();
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT customer_id, name, email FROM \"" + schemaName + "\".ecom_customers WHERE email = ? AND is_active = TRUE",
                email);
            if (rows.isEmpty()) return null;
            return rows.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    private List<Map<String, Object>> fetchCart(String schema, Integer customerId) {
        boolean hasSizes = columnExists(schema, "products", "sizes");
        String sizesCol = hasSizes ? ", p.sizes" : "";
        String sql =
            "SELECT ci.item_id, ci.product_id, ci.quantity, ci.size, " +
            "       p.product_name, p.selling_price, p.unit" + sizesCol + ", " +
            "       (SELECT pi.image_url FROM \"" + schema + "\".product_images pi " +
            "        WHERE pi.product_id = p.product_id AND (pi.media_type IS NULL OR pi.media_type = 'image') " +
            "        ORDER BY pi.image_id LIMIT 1) AS image_url " +
            "FROM \"" + schema + "\".cart_items ci " +
            "JOIN \"" + schema + "\".products p ON p.product_id = ci.product_id " +
            "WHERE ci.customer_id = ? " +
            "ORDER BY ci.created_at";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, customerId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("itemId",       row.get("item_id"));
            item.put("productId",    row.get("product_id"));
            item.put("productName",  row.get("product_name"));
            item.put("sellingPrice", row.get("selling_price"));
            item.put("unit",         row.get("unit"));
            item.put("sizes",        hasSizes ? row.get("sizes") : null);
            item.put("quantity",     row.get("quantity"));
            item.put("size",         row.get("size"));
            item.put("imageUrl",     row.get("image_url"));
            result.add(item);
        }
        return result;
    }

    @GetMapping("/{firmCode}/cart")
    public ResponseEntity<?> getCart(
            @PathVariable String firmCode,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        Optional<Firm> firmOpt = firmRepository.findByFirmCode(firmCode.toLowerCase().trim());
        if (firmOpt.isEmpty() || !Boolean.TRUE.equals(firmOpt.get().getIsActive())) {
            return ResponseEntity.notFound().build();
        }
        String s = firmOpt.get().getSchemaName();
        Map<String, Object> customer = extractEcomCustomer(s, authHeader);
        if (customer == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        Integer customerId = ((Number) customer.get("customer_id")).intValue();
        return ResponseEntity.ok(fetchCart(s, customerId));
    }

    @PostMapping("/{firmCode}/cart")
    public ResponseEntity<?> addToCart(
            @PathVariable String firmCode,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody Map<String, Object> body) {

        Optional<Firm> firmOpt = firmRepository.findByFirmCode(firmCode.toLowerCase().trim());
        if (firmOpt.isEmpty() || !Boolean.TRUE.equals(firmOpt.get().getIsActive())) {
            return ResponseEntity.notFound().build();
        }
        String s = firmOpt.get().getSchemaName();
        Map<String, Object> customer = extractEcomCustomer(s, authHeader);
        if (customer == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        Integer customerId = ((Number) customer.get("customer_id")).intValue();
        Integer productId = body.get("productId") != null ? ((Number) body.get("productId")).intValue() : null;
        if (productId == null) return ResponseEntity.badRequest().body(Map.of("error", "productId is required"));

        double quantity = body.get("quantity") != null ? ((Number) body.get("quantity")).doubleValue() : 1.0;
        String size = (String) body.get("size");

        // Check if item already exists
        String checkSql = size != null
            ? "SELECT item_id, quantity FROM \"" + s + "\".cart_items WHERE customer_id = ? AND product_id = ? AND size = ?"
            : "SELECT item_id, quantity FROM \"" + s + "\".cart_items WHERE customer_id = ? AND product_id = ? AND size IS NULL";

        List<Map<String, Object>> existing;
        if (size != null) {
            existing = jdbcTemplate.queryForList(checkSql, customerId, productId, size);
        } else {
            existing = jdbcTemplate.queryForList(checkSql, customerId, productId);
        }

        if (!existing.isEmpty()) {
            double newQty = ((Number) existing.get(0).get("quantity")).doubleValue() + quantity;
            Integer itemId = ((Number) existing.get(0).get("item_id")).intValue();
            jdbcTemplate.update("UPDATE \"" + s + "\".cart_items SET quantity = ? WHERE item_id = ?", newQty, itemId);
        } else {
            if (size != null) {
                jdbcTemplate.update(
                    "INSERT INTO \"" + s + "\".cart_items (customer_id, product_id, quantity, size) VALUES (?, ?, ?, ?)",
                    customerId, productId, quantity, size);
            } else {
                jdbcTemplate.update(
                    "INSERT INTO \"" + s + "\".cart_items (customer_id, product_id, quantity) VALUES (?, ?, ?)",
                    customerId, productId, quantity);
            }
        }

        return ResponseEntity.ok(fetchCart(s, customerId));
    }

    @PutMapping("/{firmCode}/cart/{itemId}")
    public ResponseEntity<?> updateCartItem(
            @PathVariable String firmCode,
            @PathVariable Integer itemId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody Map<String, Object> body) {

        Optional<Firm> firmOpt = firmRepository.findByFirmCode(firmCode.toLowerCase().trim());
        if (firmOpt.isEmpty() || !Boolean.TRUE.equals(firmOpt.get().getIsActive())) {
            return ResponseEntity.notFound().build();
        }
        String s = firmOpt.get().getSchemaName();
        Map<String, Object> customer = extractEcomCustomer(s, authHeader);
        if (customer == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        Integer customerId = ((Number) customer.get("customer_id")).intValue();
        double quantity = body.get("quantity") != null ? ((Number) body.get("quantity")).doubleValue() : 0;

        if (quantity <= 0) {
            jdbcTemplate.update("DELETE FROM \"" + s + "\".cart_items WHERE item_id = ? AND customer_id = ?",
                itemId, customerId);
        } else {
            jdbcTemplate.update("UPDATE \"" + s + "\".cart_items SET quantity = ? WHERE item_id = ? AND customer_id = ?",
                quantity, itemId, customerId);
        }

        return ResponseEntity.ok(fetchCart(s, customerId));
    }

    @DeleteMapping("/{firmCode}/cart/{itemId}")
    public ResponseEntity<?> removeCartItem(
            @PathVariable String firmCode,
            @PathVariable Integer itemId,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        Optional<Firm> firmOpt = firmRepository.findByFirmCode(firmCode.toLowerCase().trim());
        if (firmOpt.isEmpty() || !Boolean.TRUE.equals(firmOpt.get().getIsActive())) {
            return ResponseEntity.notFound().build();
        }
        String s = firmOpt.get().getSchemaName();
        Map<String, Object> customer = extractEcomCustomer(s, authHeader);
        if (customer == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        Integer customerId = ((Number) customer.get("customer_id")).intValue();
        jdbcTemplate.update("DELETE FROM \"" + s + "\".cart_items WHERE item_id = ? AND customer_id = ?",
            itemId, customerId);

        return ResponseEntity.ok(fetchCart(s, customerId));
    }

    @DeleteMapping("/{firmCode}/cart")
    public ResponseEntity<?> clearCart(
            @PathVariable String firmCode,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        Optional<Firm> firmOpt = firmRepository.findByFirmCode(firmCode.toLowerCase().trim());
        if (firmOpt.isEmpty() || !Boolean.TRUE.equals(firmOpt.get().getIsActive())) {
            return ResponseEntity.notFound().build();
        }
        String s = firmOpt.get().getSchemaName();
        Map<String, Object> customer = extractEcomCustomer(s, authHeader);
        if (customer == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        Integer customerId = ((Number) customer.get("customer_id")).intValue();
        jdbcTemplate.update("DELETE FROM \"" + s + "\".cart_items WHERE customer_id = ?", customerId);

        return ResponseEntity.ok(Map.of("message", "Cart cleared"));
    }

    // ── Boutique Designs ─────────────────────────────────────────────────────

    @GetMapping("/{firmCode}/boutique-designs")
    public ResponseEntity<?> getBoutiqueDesigns(
            @PathVariable String firmCode,
            @RequestParam(required = false) String garmentType) {

        Optional<Firm> firmOpt = firmRepository.findByFirmCode(firmCode.toLowerCase().trim());
        if (firmOpt.isEmpty() || !Boolean.TRUE.equals(firmOpt.get().getIsActive())) {
            return ResponseEntity.notFound().build();
        }
        String s = firmOpt.get().getSchemaName();
        if (!tableExists(s, "boutique_designs")) {
            return ResponseEntity.ok(List.of());
        }

        String sql = "SELECT design_id, garment_type, description, rough_price, delivery_days, image_url " +
                     "FROM \"" + s + "\".boutique_designs WHERE is_active = TRUE";
        List<Object> params = new ArrayList<>();
        if (garmentType != null && !garmentType.isBlank()) {
            sql += " AND garment_type = ?";
            params.add(garmentType);
        }
        sql += " ORDER BY garment_type, design_id";

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, params.toArray());
        if (rows.isEmpty()) return ResponseEntity.ok(List.of());

        // Build design map
        Map<Integer, Map<String, Object>> designMap = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            int did = ((Number) row.get("design_id")).intValue();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("designId",     did);
            item.put("garmentType",  row.get("garment_type"));
            item.put("description",  row.get("description"));
            item.put("roughPrice",   row.get("rough_price"));
            item.put("deliveryDays", row.get("delivery_days"));
            item.put("imageUrl",     row.get("image_url"));   // legacy compat
            item.put("images",       new ArrayList<String>());
            designMap.put(did, item);
        }

        // Load images from boutique_design_images if table exists
        if (tableExists(s, "boutique_design_images") && !designMap.isEmpty()) {
            String ph = designMap.keySet().stream().map(i -> "?").collect(java.util.stream.Collectors.joining(","));
            List<Map<String, Object>> imgRows = jdbcTemplate.queryForList(
                "SELECT design_id, image_url FROM \"" + s + "\".boutique_design_images " +
                "WHERE design_id IN (" + ph + ") ORDER BY design_id, id",
                designMap.keySet().toArray());
            for (Map<String, Object> img : imgRows) {
                int did = ((Number) img.get("design_id")).intValue();
                String url = (String) img.get("image_url");
                if (url != null && designMap.containsKey(did)) {
                    //noinspection unchecked
                    ((List<String>) designMap.get(did).get("images")).add(url);
                }
            }
        }

        // Fall back to legacy image_url for designs with no images in new table
        for (Map<String, Object> row : rows) {
            int did = ((Number) row.get("design_id")).intValue();
            //noinspection unchecked
            List<String> imgs = (List<String>) designMap.get(did).get("images");
            if (imgs.isEmpty()) {
                String legacyUrl = (String) row.get("image_url");
                if (legacyUrl != null && !legacyUrl.isBlank()) imgs.add(legacyUrl);
            }
        }

        return ResponseEntity.ok(new ArrayList<>(designMap.values()));
    }

    /** Returns true if the given column exists in the given table/schema. */
    private boolean columnExists(String schema, String table, String column) {
        String sql = "SELECT EXISTS (" +
                     "  SELECT 1 FROM information_schema.columns " +
                     "  WHERE table_schema = ? AND table_name = ? AND column_name = ?" +
                     ")";
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(sql, Boolean.class, schema, table, column));
    }
}

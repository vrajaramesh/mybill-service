package com.example.mybill.controller;

import com.example.mybill.boutique.config.BoutiqueSchemaInitializer;
import com.example.mybill.boutique.dto.FabricLinkDto;
import com.example.mybill.boutique.exception.AgentApiException;
import com.example.mybill.boutique.service.DesignFabricLinkService;
import com.example.mybill.multitenancy.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/boutique-designs")
public class BoutiqueDesignController {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private BoutiqueSchemaInitializer boutiqueSchemaInitializer;

    @Autowired
    private DesignFabricLinkService fabricLinks;

    // ── List all designs with their images ───────────────────────────────────

    @GetMapping
    public ResponseEntity<?> list() {
        String s = TenantContext.getCurrentTenant();
        if (s == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT design_id, code, garment_type, description, rough_price, delivery_days, " +
            "       image_url, public_id, is_active, exact_measurements, created_at " +
            "FROM \"" + s + "\".boutique_designs ORDER BY garment_type, design_id");

        if (rows.isEmpty()) return ResponseEntity.ok(List.of());

        // Build designs map
        Map<Integer, Map<String, Object>> designMap = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            int id = ((Number) row.get("design_id")).intValue();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("designId",     id);
            item.put("garmentType",  row.get("garment_type"));
            item.put("description",  row.get("description"));
            item.put("roughPrice",   row.get("rough_price"));
            item.put("deliveryDays", row.get("delivery_days"));
            item.put("isActive",     row.get("is_active"));
            item.put("code",         row.get("code"));
            item.put("exactMeasurements", Boolean.TRUE.equals(row.get("exact_measurements")));
            item.put("createdAt",    row.get("created_at"));
            item.put("images",       new ArrayList<Map<String, Object>>());
            designMap.put(id, item);
        }

        // Load images from boutique_design_images
        boolean hasImagesTable = tableExists(s, "boutique_design_images");
        if (hasImagesTable && !designMap.isEmpty()) {
            String placeholders = designMap.keySet().stream().map(i -> "?").collect(java.util.stream.Collectors.joining(","));
            List<Map<String, Object>> imgRows = jdbcTemplate.queryForList(
                "SELECT id, design_id, image_url, public_id FROM \"" + s + "\".boutique_design_images " +
                "WHERE design_id IN (" + placeholders + ") ORDER BY design_id, id",
                designMap.keySet().toArray());
            for (Map<String, Object> img : imgRows) {
                int did = ((Number) img.get("design_id")).intValue();
                if (designMap.containsKey(did)) {
                    Map<String, Object> imgItem = new LinkedHashMap<>();
                    imgItem.put("id",       ((Number) img.get("id")).intValue());
                    imgItem.put("imageUrl", img.get("image_url"));
                    imgItem.put("publicId", img.get("public_id"));
                    //noinspection unchecked
                    ((List<Map<String, Object>>) designMap.get(did).get("images")).add(imgItem);
                }
            }
        }

        // Fall back to legacy image_url for designs with no images in new table
        for (Map<String, Object> row : rows) {
            int id = ((Number) row.get("design_id")).intValue();
            //noinspection unchecked
            List<Map<String, Object>> imgs = (List<Map<String, Object>>) designMap.get(id).get("images");
            if (imgs.isEmpty()) {
                String legacyUrl = (String) row.get("image_url");
                if (legacyUrl != null && !legacyUrl.isBlank()) {
                    Map<String, Object> legacy = new LinkedHashMap<>();
                    legacy.put("id",       0);
                    legacy.put("imageUrl", legacyUrl);
                    legacy.put("publicId", row.get("public_id"));
                    imgs.add(legacy);
                }
            }
        }

        // Fabrics sold separately (website links)
        boutiqueSchemaInitializer.ensureSchema(s);   // boutique_design_fabrics (V12)
        Map<Integer, List<FabricLinkDto>> fabrics = fabricLinks.forDesigns(new ArrayList<>(designMap.keySet()));
        designMap.forEach((id, item) -> item.put("fabrics", fabrics.getOrDefault(id, List.of())));

        return ResponseEntity.ok(new ArrayList<>(designMap.values()));
    }

    // ── Create design ────────────────────────────────────────────────────────

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        String s = TenantContext.getCurrentTenant();
        if (s == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        String garmentType = (String) body.get("garmentType");
        if (garmentType == null || garmentType.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "garmentType is required"));

        String description = (String) body.get("description");
        Double roughPrice  = body.get("roughPrice")   != null ? ((Number) body.get("roughPrice")).doubleValue()   : null;
        Integer deliveryDays = body.get("deliveryDays") != null ? ((Number) body.get("deliveryDays")).intValue()  : null;

        boutiqueSchemaInitializer.ensureSchema(s);   // exact_measurements column (V9)
        boolean exact = Boolean.TRUE.equals(body.get("exactMeasurements"));
        List<Integer> generatedIds = jdbcTemplate.queryForList(
            "INSERT INTO \"" + s + "\".boutique_designs (garment_type, description, rough_price, delivery_days, exact_measurements) " +
            "VALUES (?, ?, ?, ?, ?) RETURNING design_id",
            Integer.class, garmentType, description, roughPrice, deliveryDays, exact);

        if (generatedIds.isEmpty()) return ResponseEntity.internalServerError().build();
        int designId = generatedIds.get(0);

        // Code the AI agent uses to refer to the design (e.g. "Lehenga" #7 -> LEH-7), same rule as db/boutique/V1.
        boutiqueSchemaInitializer.ensureSchema(s);
        jdbcTemplate.update(
            "UPDATE \"" + s + "\".boutique_designs SET code = COALESCE(NULLIF(UPPER(LEFT(" +
            "REGEXP_REPLACE(garment_type, '[^A-Za-z]', '', 'g'), 3)), ''), 'BD') || '-' || design_id " +
            "WHERE design_id = ? AND code IS NULL", designId);

        // Save images if provided
        saveImages(s, designId, body);

        return ResponseEntity.ok(Map.of("designId", designId));
    }

    // ── Update design ────────────────────────────────────────────────────────

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Integer id, @RequestBody Map<String, Object> body) {
        String s = TenantContext.getCurrentTenant();
        if (s == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        String garmentType   = (String) body.get("garmentType");
        String description   = (String) body.get("description");
        Double roughPrice    = body.get("roughPrice")    != null ? ((Number) body.get("roughPrice")).doubleValue()    : null;
        Integer deliveryDays = body.get("deliveryDays")  != null ? ((Number) body.get("deliveryDays")).intValue()     : null;
        Boolean isActive     = body.get("isActive") instanceof Boolean b ? b : true;
        boolean exact        = Boolean.TRUE.equals(body.get("exactMeasurements"));

        boutiqueSchemaInitializer.ensureSchema(s);   // exact_measurements column (V9)
        jdbcTemplate.update(
            "UPDATE \"" + s + "\".boutique_designs SET garment_type = ?, description = ?, rough_price = ?, " +
            "delivery_days = ?, is_active = ?, exact_measurements = ?, updated_at = NOW() WHERE design_id = ?",
            garmentType, description, roughPrice, deliveryDays, isActive, exact, id);

        return ResponseEntity.ok(Map.of("updated", true));
    }

    // ── Fabrics sold separately: links to product pages on the shop website ──

    /** Replace a design's fabric links: {"links": [{"url": "https://srisafabrics.com/?product=6223", "label": "..."}]}. */
    @PutMapping("/{id}/fabrics")
    public ResponseEntity<?> saveFabrics(@PathVariable Integer id, @RequestBody Map<String, Object> body) {
        String s = TenantContext.getCurrentTenant();
        if (s == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        boutiqueSchemaInitializer.ensureSchema(s);
        List<Map<String, Object>> links = new ArrayList<>();
        if (body.get("links") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    Map<String, Object> link = new LinkedHashMap<>();
                    m.forEach((k, v) -> link.put(String.valueOf(k), v));
                    links.add(link);
                }
            }
        }
        try {
            return ResponseEntity.ok(fabricLinks.replace(id, links));
        } catch (AgentApiException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Online products to link as a design's fabric, each with its website link. */
    @GetMapping("/fabric-search")
    public ResponseEntity<?> fabricSearch(@RequestParam(defaultValue = "") String q) {
        String s = TenantContext.getCurrentTenant();
        if (s == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        String like = "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT product_id, product_name, category, unit, selling_price, stock_quantity FROM \"" + s + "\".products " +
            "WHERE is_active = TRUE AND COALESCE(is_online, FALSE) = TRUE " +
            "AND (LOWER(product_name) LIKE ? OR LOWER(COALESCE(category, '')) LIKE ? OR CAST(product_id AS TEXT) = ?) " +
            "ORDER BY stock_quantity DESC NULLS LAST, product_id DESC LIMIT 20", like, like, q.trim());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            int pid = ((Number) r.get("product_id")).intValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("productId", pid);
            m.put("name", r.get("product_name"));
            m.put("category", r.get("category"));
            m.put("unit", r.get("unit"));
            m.put("price", r.get("selling_price"));
            m.put("stock", r.get("stock_quantity"));
            m.put("url", fabricLinks.productUrl(pid));
            out.add(m);
        }
        return ResponseEntity.ok(out);
    }

    // ── Delete design ────────────────────────────────────────────────────────

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Integer id) {
        String s = TenantContext.getCurrentTenant();
        if (s == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        if (tableExists(s, "boutique_design_images")) {
            jdbcTemplate.update("DELETE FROM \"" + s + "\".boutique_design_images WHERE design_id = ?", id);
        }
        jdbcTemplate.update("DELETE FROM \"" + s + "\".boutique_designs WHERE design_id = ?", id);
        return ResponseEntity.ok(Map.of("deleted", true));
    }

    // ── Add image to design ──────────────────────────────────────────────────

    @PostMapping("/{id}/images")
    public ResponseEntity<?> addImage(@PathVariable Integer id, @RequestBody Map<String, Object> body) {
        String s = TenantContext.getCurrentTenant();
        if (s == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        String imageUrl = (String) body.get("imageUrl");
        String publicId = (String) body.get("publicId");
        if (imageUrl == null || imageUrl.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "imageUrl is required"));

        List<Integer> ids = jdbcTemplate.queryForList(
            "INSERT INTO \"" + s + "\".boutique_design_images (design_id, image_url, public_id) " +
            "VALUES (?, ?, ?) RETURNING id", Integer.class, id, imageUrl, publicId);

        return ResponseEntity.ok(Map.of("id", ids.isEmpty() ? 0 : ids.get(0), "imageUrl", imageUrl));
    }

    // ── Delete image from design ─────────────────────────────────────────────

    @DeleteMapping("/{id}/images/{imgId}")
    public ResponseEntity<?> deleteImage(@PathVariable Integer id, @PathVariable Integer imgId) {
        String s = TenantContext.getCurrentTenant();
        if (s == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        jdbcTemplate.update(
            "DELETE FROM \"" + s + "\".boutique_design_images WHERE id = ? AND design_id = ?", imgId, id);
        return ResponseEntity.ok(Map.of("deleted", true));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void saveImages(String s, int designId, Map<String, Object> body) {
        if (!tableExists(s, "boutique_design_images")) return;
        Object imagesObj = body.get("images");
        if (!(imagesObj instanceof List)) return;
        for (Object imgObj : (List<?>) imagesObj) {
            if (!(imgObj instanceof Map)) continue;
            Map<String, Object> img = (Map<String, Object>) imgObj;
            String url = (String) img.get("imageUrl");
            String pid = (String) img.get("publicId");
            if (url != null && !url.isBlank()) {
                jdbcTemplate.update(
                    "INSERT INTO \"" + s + "\".boutique_design_images (design_id, image_url, public_id) VALUES (?, ?, ?)",
                    designId, url, pid);
            }
        }
    }

    private boolean tableExists(String schema, String table) {
        String sql = "SELECT EXISTS (SELECT 1 FROM information_schema.tables " +
                     "WHERE table_schema = ? AND table_name = ?)";
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(sql, Boolean.class, schema, table));
    }
}

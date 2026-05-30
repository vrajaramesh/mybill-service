package com.example.mybill.controller;

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

    // ── List all designs with their images ───────────────────────────────────

    @GetMapping
    public ResponseEntity<?> list() {
        String s = TenantContext.getCurrentTenant();
        if (s == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT design_id, garment_type, description, rough_price, delivery_days, " +
            "       image_url, public_id, is_active, created_at " +
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

        List<Integer> generatedIds = jdbcTemplate.queryForList(
            "INSERT INTO \"" + s + "\".boutique_designs (garment_type, description, rough_price, delivery_days) " +
            "VALUES (?, ?, ?, ?) RETURNING design_id",
            Integer.class, garmentType, description, roughPrice, deliveryDays);

        if (generatedIds.isEmpty()) return ResponseEntity.internalServerError().build();
        int designId = generatedIds.get(0);

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

        jdbcTemplate.update(
            "UPDATE \"" + s + "\".boutique_designs SET garment_type = ?, description = ?, rough_price = ?, " +
            "delivery_days = ?, is_active = ?, updated_at = NOW() WHERE design_id = ?",
            garmentType, description, roughPrice, deliveryDays, isActive, id);

        return ResponseEntity.ok(Map.of("updated", true));
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

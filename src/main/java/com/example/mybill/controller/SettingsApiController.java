package com.example.mybill.controller;

import com.example.mybill.multitenancy.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Persists firm-level settings (key-value) in the firm_settings table.
 * Requires ADMIN-scoped JWT (routed via TenantFilter).
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsApiController {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @GetMapping
    public ResponseEntity<?> getSettings() {
        String s = TenantContext.getCurrentTenant();
        if (s == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT key, value FROM \"" + s + "\".firm_settings ORDER BY key");

        Map<String, String> result = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            result.put((String) row.get("key"), (String) row.get("value"));
        }
        return ResponseEntity.ok(result);
    }

    @PutMapping
    public ResponseEntity<?> saveSettings(@RequestBody Map<String, String> settings) {
        String s = TenantContext.getCurrentTenant();
        if (s == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        for (Map.Entry<String, String> entry : settings.entrySet()) {
            jdbcTemplate.update(
                "INSERT INTO \"" + s + "\".firm_settings (key, value) VALUES (?, ?) " +
                "ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value",
                entry.getKey(), entry.getValue());
        }
        return ResponseEntity.ok(Map.of("saved", settings.size()));
    }
}

package com.example.mybill.controller;

import com.example.mybill.dto.AppUserPublic;
import com.example.mybill.dto.Firm;
import com.example.mybill.multitenancy.JwtUtil;
import com.example.mybill.repository.AppUserPublicRepository;
import com.example.mybill.repository.FirmRepository;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import javax.sql.DataSource;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/admin")
public class AdminUserController {

    @Autowired private JwtUtil jwtUtil;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DataSource dataSource;
    @Autowired private FirmRepository firmRepository;
    @Autowired private AppUserPublicRepository appUserPublicRepository;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Claims claims(HttpServletRequest req) {
        String h = req.getHeader("Authorization");
        if (h == null || !h.startsWith("Bearer ")) throw new SecurityException("Token required");
        return jwtUtil.extractClaims(h.substring(7));
    }

    private boolean isSA(Claims c) { return "SUPERADMIN".equals(c.get("role", String.class)); }
    private boolean isAdmin(Claims c) { return "ADMIN".equals(c.get("role", String.class)); }

    private String schemaFor(String firmCode) {
        return firmRepository.findByFirmCode(firmCode.toLowerCase().trim())
            .map(Firm::getSchemaName)
            .orElseThrow(() -> new IllegalArgumentException("Firm not found: " + firmCode));
    }

    /** Verify the calling admin has access to the given firmId. */
    private boolean adminHasAccess(String adminUsername, long firmId) {
        return appUserPublicRepository.findActiveByUsername(adminUsername)
            .map(admin -> {
                Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM public.admin_firm_access WHERE admin_id = ? AND firm_id = ? AND is_active = TRUE",
                    Integer.class, admin.getUserId(), firmId);
                return count != null && count > 0;
            })
            .orElse(false);
    }

    private List<Map<String, Object>> usersFromSchema(String schema) throws Exception {
        try (Connection c = dataSource.getConnection()) {
            c.createStatement().execute("SET search_path TO \"" + schema + "\", public");
            try (ResultSet rs = c.createStatement().executeQuery(
                "SELECT user_id, username, full_name, email, phone, role, is_active, created_at, last_login_at " +
                "FROM app_users ORDER BY full_name")) {
                List<Map<String, Object>> rows = new ArrayList<>();
                ResultSetMetaData meta = rs.getMetaData();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= meta.getColumnCount(); i++)
                        row.put(meta.getColumnLabel(i), rs.getObject(i));
                    rows.add(row);
                }
                return rows;
            }
        }
    }

    // ── Accessible firms ─────────────────────────────────────────────────────

    @GetMapping("/firms")
    public ResponseEntity<?> getFirms(HttpServletRequest req) {
        try {
            Claims c = claims(req);
            if (isSA(c)) return ResponseEntity.ok(firmRepository.findAll());
            if (isAdmin(c)) {
                Optional<AppUserPublic> admin = appUserPublicRepository.findActiveByUsername(c.getSubject());
                if (admin.isEmpty()) return ResponseEntity.status(403).build();
                return ResponseEntity.ok(jdbcTemplate.queryForList(
                    "SELECT f.firm_id, f.firm_name, f.firm_code, f.schema_name, f.is_active " +
                    "FROM public.admin_firm_access afa JOIN public.firms f ON f.firm_id = afa.firm_id " +
                    "WHERE afa.admin_id = ? AND afa.is_active = TRUE", admin.get().getUserId()));
            }
            return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        } catch (Exception e) { return ResponseEntity.status(403).body(Map.of("error", e.getMessage())); }
    }

    // ── Sales users ───────────────────────────────────────────────────────────

    /** List users. SA must pass ?firmCode. ADMIN uses their JWT schema. */
    @GetMapping("/users")
    public ResponseEntity<?> getUsers(@RequestParam(required = false) String firmCode,
                                       HttpServletRequest req) {
        try {
            Claims c = claims(req);
            String schema;
            if (isSA(c)) {
                if (firmCode == null) return ResponseEntity.badRequest().body(Map.of("error", "firmCode required"));
                schema = schemaFor(firmCode);
            } else if (isAdmin(c)) {
                schema = c.get("schemaName", String.class);
            } else {
                return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
            }
            return ResponseEntity.ok(usersFromSchema(schema));
        } catch (Exception e) { return ResponseEntity.status(400).body(Map.of("error", e.getMessage())); }
    }

    /** List active SALES users for the current firm — used for the billing dropdown. */
    @GetMapping("/sales-persons")
    public ResponseEntity<?> getSalesPersons(HttpServletRequest req) {
        try {
            Claims c = claims(req);
            String schema = c.get("schemaName", String.class);
            if ("public".equals(schema)) return ResponseEntity.badRequest().body(Map.of("error", "No firm context"));
            try (Connection conn = dataSource.getConnection()) {
                conn.createStatement().execute("SET search_path TO \"" + schema + "\", public");
                try (ResultSet rs = conn.createStatement().executeQuery(
                    "SELECT user_id, username, full_name FROM app_users WHERE is_active = TRUE ORDER BY full_name")) {
                    List<Map<String, Object>> rows = new ArrayList<>();
                    while (rs.next())
                        rows.add(Map.of("userId", rs.getInt("user_id"),
                                        "username", rs.getString("username"),
                                        "fullName", nullSafe(rs.getString("full_name"))));
                    return ResponseEntity.ok(rows);
                }
            }
        } catch (Exception e) { return ResponseEntity.status(500).body(Map.of("error", e.getMessage())); }
    }

    /** Create a SALES user in a firm. firmCode in body. */
    @PostMapping("/users/sales")
    public ResponseEntity<?> createSalesUser(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        try {
            Claims c = claims(req);
            String firmCode = (String) body.get("firmCode");
            if (firmCode == null) return ResponseEntity.badRequest().body(Map.of("error", "firmCode required"));

            Optional<Firm> firmOpt = firmRepository.findByFirmCode(firmCode.toLowerCase().trim());
            if (firmOpt.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "Firm not found"));
            Firm firm = firmOpt.get();

            if (isAdmin(c) && !adminHasAccess(c.getSubject(), firm.getFirmId())) {
                return ResponseEntity.status(403).body(Map.of("error", "You do not have access to firm: " + firmCode));
            }
            if (!isSA(c) && !isAdmin(c)) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));

            String username = trim(body, "username");
            String password = (String) body.get("password");
            String fullName = trim(body, "fullName");
            String email    = (String) body.get("email");
            String phone    = (String) body.get("phone");

            if (username == null || password == null)
                return ResponseEntity.badRequest().body(Map.of("error", "username and password are required"));
            if (password.length() < 6)
                return ResponseEntity.badRequest().body(Map.of("error", "Password must be at least 6 characters"));

            String schema = firm.getSchemaName();
            String hash   = passwordEncoder.encode(password);

            try (Connection conn = dataSource.getConnection()) {
                conn.createStatement().execute("SET search_path TO \"" + schema + "\", public");
                PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO app_users(username, password_hash, full_name, email, phone, role, is_active, created_at) " +
                    "VALUES(?,?,?,?,?,'SALES',TRUE,NOW()) RETURNING user_id, username, full_name, role, is_active");
                ps.setString(1, username);
                ps.setString(2, hash);
                ps.setString(3, fullName);
                ps.setString(4, email);
                ps.setString(5, phone);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    return ResponseEntity.ok(Map.of(
                        "userId",   rs.getInt("user_id"),
                        "username", rs.getString("username"),
                        "fullName", nullSafe(rs.getString("full_name")),
                        "role",     rs.getString("role"),
                        "firmCode", firmCode,
                        "message",  "Sales user created"
                    ));
                }
            }
            return ResponseEntity.status(500).body(Map.of("error", "Insert failed"));
        } catch (Exception e) {
            String msg = e.getMessage() != null && e.getMessage().contains("unique")
                ? "Username already exists in this firm" : e.getMessage();
            return ResponseEntity.badRequest().body(Map.of("error", msg));
        }
    }

    /** Update a SALES user. firmCode in query param. */
    @PutMapping("/users/sales/{userId}")
    public ResponseEntity<?> updateSalesUser(@PathVariable int userId,
                                              @RequestParam(required = false) String firmCode,
                                              @RequestBody Map<String, Object> body,
                                              HttpServletRequest req) {
        try {
            Claims c = claims(req);
            String schema;
            if (isSA(c)) {
                if (firmCode == null) return ResponseEntity.badRequest().body(Map.of("error", "firmCode required"));
                schema = schemaFor(firmCode);
            } else if (isAdmin(c)) {
                schema = c.get("schemaName", String.class);
            } else {
                return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
            }

            try (Connection conn = dataSource.getConnection()) {
                conn.createStatement().execute("SET search_path TO \"" + schema + "\", public");
                String password = (String) body.get("password");
                PreparedStatement ps;
                if (password != null && !password.isBlank()) {
                    if (password.length() < 6) return ResponseEntity.badRequest().body(Map.of("error", "Password too short"));
                    ps = conn.prepareStatement(
                        "UPDATE app_users SET full_name=?, email=?, phone=?, is_active=?, password_hash=? WHERE user_id=?");
                    ps.setString(1, trim(body, "fullName"));
                    ps.setString(2, (String) body.get("email"));
                    ps.setString(3, (String) body.get("phone"));
                    ps.setBoolean(4, Boolean.TRUE.equals(body.get("isActive")));
                    ps.setString(5, passwordEncoder.encode(password));
                    ps.setInt(6, userId);
                } else {
                    ps = conn.prepareStatement(
                        "UPDATE app_users SET full_name=?, email=?, phone=?, is_active=? WHERE user_id=?");
                    ps.setString(1, trim(body, "fullName"));
                    ps.setString(2, (String) body.get("email"));
                    ps.setString(3, (String) body.get("phone"));
                    ps.setBoolean(4, Boolean.TRUE.equals(body.get("isActive")));
                    ps.setInt(5, userId);
                }
                ps.executeUpdate();
            }
            return ResponseEntity.ok(Map.of("message", "Updated"));
        } catch (Exception e) { return ResponseEntity.status(400).body(Map.of("error", e.getMessage())); }
    }

    /** Toggle active/inactive for a SALES user. */
    @PatchMapping("/users/sales/{userId}/status")
    public ResponseEntity<?> toggleSalesUserStatus(@PathVariable int userId,
                                                    @RequestParam(required = false) String firmCode,
                                                    @RequestBody Map<String, Object> body,
                                                    HttpServletRequest req) {
        try {
            Claims c = claims(req);
            String schema;
            if (isSA(c)) {
                if (firmCode == null) return ResponseEntity.badRequest().body(Map.of("error", "firmCode required"));
                schema = schemaFor(firmCode);
            } else if (isAdmin(c)) {
                schema = c.get("schemaName", String.class);
            } else {
                return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
            }
            boolean active = Boolean.TRUE.equals(body.get("isActive"));
            try (Connection conn = dataSource.getConnection()) {
                conn.createStatement().execute("SET search_path TO \"" + schema + "\", public");
                PreparedStatement ps = conn.prepareStatement("UPDATE app_users SET is_active=? WHERE user_id=?");
                ps.setBoolean(1, active); ps.setInt(2, userId); ps.executeUpdate();
            }
            return ResponseEntity.ok(Map.of("message", "Status updated"));
        } catch (Exception e) { return ResponseEntity.status(400).body(Map.of("error", e.getMessage())); }
    }

    // ── Admin users (SUPERADMIN only) ────────────────────────────────────────

    /** List all ADMIN users with their firm assignments. */
    @GetMapping("/admins")
    public ResponseEntity<?> getAdmins(HttpServletRequest req) {
        try {
            Claims c = claims(req);
            if (!isSA(c)) return ResponseEntity.status(403).body(Map.of("error", "Superadmin required"));

            List<Map<String, Object>> admins = jdbcTemplate.queryForList(
                "SELECT u.user_id, u.username, u.full_name, u.email, u.phone, u.is_active, u.created_at, u.last_login_at " +
                "FROM public.app_users_public u WHERE u.role = 'ADMIN' ORDER BY u.full_name");

            for (Map<String, Object> admin : admins) {
                List<Map<String, Object>> firms = jdbcTemplate.queryForList(
                    "SELECT f.firm_id, f.firm_name, f.firm_code, afa.is_active " +
                    "FROM public.admin_firm_access afa JOIN public.firms f ON f.firm_id = afa.firm_id " +
                    "WHERE afa.admin_id = ? ORDER BY f.firm_name", admin.get("user_id"));
                admin.put("firms", firms);
            }
            return ResponseEntity.ok(admins);
        } catch (Exception e) { return ResponseEntity.status(500).body(Map.of("error", e.getMessage())); }
    }

    /** Create a new ADMIN user. */
    @PostMapping("/admins")
    public ResponseEntity<?> createAdmin(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        try {
            Claims c = claims(req);
            if (!isSA(c)) return ResponseEntity.status(403).body(Map.of("error", "Superadmin required"));

            String username = trim(body, "username");
            String password = (String) body.get("password");
            String fullName = trim(body, "fullName");
            String email    = (String) body.get("email");
            String phone    = (String) body.get("phone");

            if (username == null || password == null)
                return ResponseEntity.badRequest().body(Map.of("error", "username and password are required"));
            if (password.length() < 6)
                return ResponseEntity.badRequest().body(Map.of("error", "Password must be at least 6 characters"));
            if (appUserPublicRepository.findByUsername(username).isPresent())
                return ResponseEntity.badRequest().body(Map.of("error", "Username already exists"));

            AppUserPublic admin = new AppUserPublic();
            admin.setUsername(username);
            admin.setPasswordHash(passwordEncoder.encode(password));
            admin.setFullName(fullName);
            admin.setEmail(email);
            admin.setPhone(phone);
            admin.setRole("ADMIN");
            admin.setIsActive(true);
            admin.setCreatedAt(LocalDateTime.now());
            AppUserPublic saved = appUserPublicRepository.save(admin);

            // Optionally assign to firms passed in body
            @SuppressWarnings("unchecked")
            List<String> firmCodes = (List<String>) body.get("firmCodes");
            if (firmCodes != null) {
                for (String code : firmCodes) {
                    firmRepository.findByFirmCode(code.toLowerCase().trim()).ifPresent(firm ->
                        jdbcTemplate.update(
                            "INSERT INTO public.admin_firm_access(admin_id,firm_id,firm_code,is_active,assigned_by,created_at) " +
                            "VALUES(?,?,?,TRUE,?,NOW()) ON CONFLICT(admin_id,firm_id) DO UPDATE SET is_active=TRUE",
                            saved.getUserId(), firm.getFirmId(), firm.getFirmCode(), c.getSubject()));
                }
            }
            return ResponseEntity.ok(Map.of(
                "userId",   saved.getUserId(),
                "username", saved.getUsername(),
                "fullName", nullSafe(saved.getFullName()),
                "message",  "Admin user created"
            ));
        } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }

    /** Update an ADMIN user's profile/password. */
    @PutMapping("/admins/{adminId}")
    public ResponseEntity<?> updateAdmin(@PathVariable int adminId,
                                          @RequestBody Map<String, Object> body,
                                          HttpServletRequest req) {
        try {
            Claims c = claims(req);
            if (!isSA(c)) return ResponseEntity.status(403).body(Map.of("error", "Superadmin required"));

            Optional<AppUserPublic> opt = appUserPublicRepository.findById(adminId);
            if (opt.isEmpty() || !"ADMIN".equals(opt.get().getRole()))
                return ResponseEntity.notFound().build();
            AppUserPublic admin = opt.get();
            admin.setFullName(trim(body, "fullName"));
            admin.setEmail((String) body.get("email"));
            admin.setPhone((String) body.get("phone"));
            if (body.containsKey("isActive")) admin.setIsActive(Boolean.TRUE.equals(body.get("isActive")));
            String password = (String) body.get("password");
            if (password != null && !password.isBlank()) {
                if (password.length() < 6) return ResponseEntity.badRequest().body(Map.of("error", "Password too short"));
                admin.setPasswordHash(passwordEncoder.encode(password));
            }
            appUserPublicRepository.save(admin);
            return ResponseEntity.ok(Map.of("message", "Admin updated"));
        } catch (Exception e) { return ResponseEntity.status(400).body(Map.of("error", e.getMessage())); }
    }

    /** Assign an admin to a firm. */
    @PostMapping("/admins/{adminId}/firms/{firmId}")
    public ResponseEntity<?> assignAdminToFirm(@PathVariable int adminId, @PathVariable long firmId,
                                                HttpServletRequest req) {
        try {
            Claims c = claims(req);
            if (!isSA(c)) return ResponseEntity.status(403).body(Map.of("error", "Superadmin required"));

            Optional<AppUserPublic> adminOpt = appUserPublicRepository.findById(adminId);
            if (adminOpt.isEmpty() || !"ADMIN".equals(adminOpt.get().getRole()))
                return ResponseEntity.badRequest().body(Map.of("error", "Admin user not found"));
            Optional<Firm> firmOpt = firmRepository.findById(firmId);
            if (firmOpt.isEmpty())
                return ResponseEntity.badRequest().body(Map.of("error", "Firm not found"));
            Firm firm = firmOpt.get();

            jdbcTemplate.update(
                "INSERT INTO public.admin_firm_access(admin_id,firm_id,firm_code,is_active,assigned_by,created_at) " +
                "VALUES(?,?,?,TRUE,?,NOW()) ON CONFLICT(admin_id,firm_id) DO UPDATE SET is_active=TRUE, assigned_by=?",
                adminId, firmId, firm.getFirmCode(), c.getSubject(), c.getSubject());

            return ResponseEntity.ok(Map.of("message", "Admin assigned to firm " + firm.getFirmCode()));
        } catch (Exception e) { return ResponseEntity.status(400).body(Map.of("error", e.getMessage())); }
    }

    /** Remove an admin from a firm (soft delete). */
    @DeleteMapping("/admins/{adminId}/firms/{firmId}")
    public ResponseEntity<?> removeAdminFromFirm(@PathVariable int adminId, @PathVariable long firmId,
                                                   HttpServletRequest req) {
        try {
            Claims c = claims(req);
            if (!isSA(c)) return ResponseEntity.status(403).body(Map.of("error", "Superadmin required"));
            jdbcTemplate.update(
                "UPDATE public.admin_firm_access SET is_active=FALSE WHERE admin_id=? AND firm_id=?",
                adminId, firmId);
            return ResponseEntity.ok(Map.of("message", "Access removed"));
        } catch (Exception e) { return ResponseEntity.status(400).body(Map.of("error", e.getMessage())); }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String trim(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v instanceof String s ? s.trim() : null;
    }

    private String nullSafe(String s) { return s != null ? s : ""; }
}

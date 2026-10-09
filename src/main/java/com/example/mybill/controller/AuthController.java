package com.example.mybill.controller;

import com.example.mybill.dto.AppUserPublic;
import com.example.mybill.dto.Firm;
import com.example.mybill.dto.LoginResponse;
import com.example.mybill.multitenancy.JwtUtil;
import com.example.mybill.repository.AppUserPublicRepository;
import com.example.mybill.repository.FirmRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired private FirmRepository firmRepository;
    @Autowired private AppUserPublicRepository appUserPublicRepository;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    // ── Login ─────────────────────────────────────────────────────────────────

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> credentials) {
        String username = credentials.get("username");
        String password = credentials.get("password");
        String firmCode = credentials.get("firmCode");

        // ── 1. Check public schema (SUPERADMIN or ADMIN) ──────────────────────
        Optional<AppUserPublic> publicUserOpt = appUserPublicRepository.findActiveByUsername(username);
        if (publicUserOpt.isPresent()) {
            AppUserPublic pu = publicUserOpt.get();
            if (!passwordEncoder.matches(password, pu.getPasswordHash())) {
                return ResponseEntity.status(401).body(Map.of("error", "Invalid username or password"));
            }
            if ("SUPERADMIN".equals(pu.getRole())) {
                return superadminLoginResponse(pu);
            }
            if ("ADMIN".equals(pu.getRole())) {
                return adminLoginResponse(pu, firmCode);
            }
        }

        // ── 2. Search firm schemas for SALES / firm-ADMIN users ──────────────
        if (firmCode == null || firmCode.isBlank()) {
            List<Firm> matchingFirms = findFirmsWithValidCredentials(username, password);
            if (matchingFirms.isEmpty())
                return ResponseEntity.status(401).body(Map.of("error", "Invalid username or password"));
            if (matchingFirms.size() == 1)
                return loginToFirm(username, password, matchingFirms.get(0).getFirmCode());
            return multiFirmSelectionResponse(username, matchingFirms);
        }
        return loginToFirm(username, password, firmCode);
    }

    @PostMapping("/superadmin/enter-firm")
    public ResponseEntity<?> superadminEnterFirm(
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer "))
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        String token = authHeader.substring(7);
        if (!jwtUtil.isValid(token))
            return ResponseEntity.status(401).body(Map.of("error", "Invalid token"));
        var claims = jwtUtil.extractClaims(token);
        if (!"SUPERADMIN".equals(claims.get("role", String.class)))
            return ResponseEntity.status(403).body(Map.of("error", "Only superadmin can enter a firm"));

        String firmCode = body.get("firmCode");
        if (firmCode == null || firmCode.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "firmCode is required"));

        Optional<Firm> firmOpt = firmRepository.findByFirmCode(firmCode.toLowerCase().trim());
        if (firmOpt.isEmpty())
            return ResponseEntity.status(404).body(Map.of("error", "Firm not found"));
        Firm firm = firmOpt.get();
        if (!Boolean.TRUE.equals(firm.getIsActive()))
            return ResponseEntity.status(403).body(Map.of("error", "Firm is inactive"));

        String username = claims.getSubject();
        Optional<AppUserPublic> saOpt = appUserPublicRepository.findActiveByUsername(username);
        if (saOpt.isEmpty())
            return ResponseEntity.status(404).body(Map.of("error", "User not found"));
        AppUserPublic sa = saOpt.get();

        // Generate an ADMIN-scoped token so TenantFilter routes to firm schema
        String firmToken = jwtUtil.generateToken(username, firm.getSchemaName(), firm.getFirmId(), "ADMIN");
        LoginResponse r = new LoginResponse();
        r.setToken(firmToken);
        r.setUserId(sa.getUserId());
        r.setUsername(sa.getUsername());
        r.setFullName(sa.getFullName());
        r.setRole("ADMIN");
        r.setIsSuperadmin(true);
        r.setFirmId(firm.getFirmId().intValue());
        r.setFirmName(firm.getFirmName());
        r.setFirmCode(firm.getFirmCode());
        r.setAccessLevel("ADMIN");
        r.setEmail(nullSafe(sa.getEmail()));
        r.setPhone(nullSafe(sa.getPhone()));
        r.setRequiresFirmSelection(false);
        return ResponseEntity.ok(r);
    }

    @PostMapping("/login-with-firm")
    public ResponseEntity<?> loginWithFirm(@RequestBody Map<String, String> credentials) {
        String username = credentials.get("username");
        String password = credentials.get("password");
        String firmCode = credentials.get("firmCode");
        if (firmCode == null || firmCode.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "firmCode is required"));

        // Also handle public-schema ADMIN via firm selection
        Optional<AppUserPublic> publicUserOpt = appUserPublicRepository.findActiveByUsername(username);
        if (publicUserOpt.isPresent()) {
            AppUserPublic pu = publicUserOpt.get();
            if (!passwordEncoder.matches(password, pu.getPasswordHash()))
                return ResponseEntity.status(401).body(Map.of("error", "Invalid username or password"));
            if ("ADMIN".equals(pu.getRole()))
                return adminLoginResponse(pu, firmCode);
        }

        return loginToFirm(username, password, firmCode);
    }

    // ── Sliding session: swap a still-valid token for a fresh one ─────────────

    /**
     * Re-issues the caller's token with a new expiry so an app that is used regularly never needs a re-login.
     * Only a valid, unexpired token is accepted, and the user / firm (and admin firm access) must still be active.
     */
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer "))
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        String token = authHeader.substring(7);
        if (!jwtUtil.isValid(token))
            return ResponseEntity.status(401).body(Map.of("error", "Token is invalid or expired"));

        var claims = jwtUtil.extractClaims(token);
        String username = claims.getSubject();
        String role = claims.get("role", String.class);
        Number firmIdClaim = claims.get("firmId", Number.class);
        long firmId = firmIdClaim != null ? firmIdClaim.longValue() : 0L;
        Optional<AppUserPublic> publicUser = appUserPublicRepository.findActiveByUsername(username);

        if ("SUPERADMIN".equals(role)) {
            if (publicUser.isEmpty() || !"SUPERADMIN".equals(publicUser.get().getRole()))
                return ResponseEntity.status(401).body(Map.of("error", "User is no longer active"));
            return ResponseEntity.ok(Map.of("token", jwtUtil.generateToken(username, "public", 0L, "SUPERADMIN")));
        }

        Optional<Firm> firmOpt = firmRepository.findById(firmId);
        if (firmOpt.isEmpty() || !Boolean.TRUE.equals(firmOpt.get().getIsActive()))
            return ResponseEntity.status(401).body(Map.of("error", "Firm is inactive"));
        Firm firm = firmOpt.get();
        String schema = firm.getSchemaName();

        String newRole;
        if (publicUser.isPresent() && "SUPERADMIN".equals(publicUser.get().getRole())) {
            newRole = "ADMIN";   // superadmin inside a firm context
        } else if (publicUser.isPresent() && "ADMIN".equals(publicUser.get().getRole())) {
            Integer access = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM public.admin_firm_access WHERE admin_id = ? AND firm_id = ? AND is_active = TRUE",
                Integer.class, publicUser.get().getUserId(), firm.getFirmId());
            if (access == null || access == 0)
                return ResponseEntity.status(401).body(Map.of("error", "Firm access was removed"));
            newRole = "ADMIN";
        } else {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT role, is_active FROM \"" + schema + "\".app_users WHERE username = ?", username);
            if (rows.isEmpty() || !Boolean.TRUE.equals(rows.get(0).get("is_active")))
                return ResponseEntity.status(401).body(Map.of("error", "User is no longer active"));
            newRole = (String) rows.get(0).get("role");
        }
        if (!Objects.equals(newRole, role))
            return ResponseEntity.status(401).body(Map.of("error", "Role changed — please sign in again"));

        return ResponseEntity.ok(Map.of("token", jwtUtil.generateToken(username, schema, firm.getFirmId(), newRole)));
    }

    // ── SUPERADMIN response ───────────────────────────────────────────────────

    private ResponseEntity<?> superadminLoginResponse(AppUserPublic sa) {
        jdbcTemplate.update("UPDATE public.app_users_public SET last_login_at = NOW() WHERE user_id = ?", sa.getUserId());
        String token = jwtUtil.generateToken(sa.getUsername(), "public", 0L, "SUPERADMIN");
        LoginResponse r = new LoginResponse();
        r.setToken(token);
        r.setUserId(sa.getUserId());
        r.setUsername(sa.getUsername());
        r.setFullName(sa.getFullName());
        r.setEmail(nullSafe(sa.getEmail()));
        r.setPhone(nullSafe(sa.getPhone()));
        r.setRole("SUPERADMIN");
        r.setIsSuperadmin(true);
        r.setRequiresFirmSelection(false);
        return ResponseEntity.ok(r);
    }

    // ── ADMIN response ────────────────────────────────────────────────────────

    private ResponseEntity<?> adminLoginResponse(AppUserPublic admin, String requestedFirmCode) {
        List<Map<String, Object>> firms = jdbcTemplate.queryForList(
            "SELECT f.firm_id, f.firm_name, f.firm_code, f.schema_name " +
            "FROM public.admin_firm_access afa JOIN public.firms f ON f.firm_id = afa.firm_id " +
            "WHERE afa.admin_id = ? AND afa.is_active = TRUE AND f.is_active = TRUE",
            admin.getUserId());

        if (firms.isEmpty())
            return ResponseEntity.status(403).body(Map.of("error", "No firms are assigned to this admin account"));

        if (requestedFirmCode != null && !requestedFirmCode.isBlank()) {
            String code = requestedFirmCode.toLowerCase().trim();
            Optional<Map<String, Object>> match = firms.stream()
                .filter(f -> code.equals(f.get("firm_code")))
                .findFirst();
            if (match.isEmpty())
                return ResponseEntity.status(403).body(Map.of("error", "Admin does not have access to firm: " + requestedFirmCode));
            return buildAdminFirmToken(admin, match.get());
        }

        if (firms.size() == 1)
            return buildAdminFirmToken(admin, firms.get(0));

        // Multiple firms — return selection list
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> f : firms) {
            Map<String, Object> item = new HashMap<>();
            item.put("firmId", f.get("firm_id"));
            item.put("firmName", f.get("firm_name"));
            item.put("firmCode", f.get("firm_code"));
            item.put("accessLevel", "ADMIN");
            item.put("isPrimary", false);
            list.add(item);
        }
        return ResponseEntity.ok(Map.of(
            "requiresFirmSelection", true,
            "username", admin.getUsername(),
            "availableFirms", list
        ));
    }

    private ResponseEntity<?> buildAdminFirmToken(AppUserPublic admin, Map<String, Object> firm) {
        Long firmId = ((Number) firm.get("firm_id")).longValue();
        String firmCode   = (String) firm.get("firm_code");
        String firmName   = (String) firm.get("firm_name");
        String schemaName = (String) firm.get("schema_name");

        jdbcTemplate.update("UPDATE public.app_users_public SET last_login_at = NOW() WHERE user_id = ?", admin.getUserId());

        String token = jwtUtil.generateToken(admin.getUsername(), schemaName, firmId, "ADMIN");
        LoginResponse r = new LoginResponse();
        r.setToken(token);
        r.setUserId(admin.getUserId());
        r.setUsername(admin.getUsername());
        r.setFullName(admin.getFullName());
        r.setEmail(nullSafe(admin.getEmail()));
        r.setPhone(nullSafe(admin.getPhone()));
        r.setRole("ADMIN");
        r.setIsSuperadmin(false);
        r.setFirmId(firmId.intValue());
        r.setFirmName(firmName);
        r.setFirmCode(firmCode);
        r.setAccessLevel("ADMIN");
        r.setRequiresFirmSelection(false);
        return ResponseEntity.ok(r);
    }

    // ── Firm-schema login (SALES users) ──────────────────────────────────────

    private ResponseEntity<?> loginToFirm(String username, String password, String firmCode) {
        Optional<Firm> firmOpt = firmRepository.findByFirmCode(firmCode.toLowerCase().trim());
        if (firmOpt.isEmpty())
            return ResponseEntity.status(401).body(Map.of("error", "Firm not found"));
        Firm firm = firmOpt.get();
        if (!Boolean.TRUE.equals(firm.getIsActive()))
            return ResponseEntity.status(403).body(Map.of("error", "Firm is inactive"));

        String schema = firm.getSchemaName();
        String sql = "SELECT user_id, username, password_hash, full_name, role, is_active, email, phone " +
                     "FROM \"" + schema + "\".app_users WHERE username = ?";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, username);
        if (rows.isEmpty())
            return ResponseEntity.status(401).body(Map.of("error", "Invalid username or password"));

        Map<String, Object> row = rows.get(0);
        if (!Boolean.TRUE.equals(row.get("is_active"))
                || !passwordEncoder.matches(password, (String) row.get("password_hash")))
            return ResponseEntity.status(401).body(Map.of("error", "Invalid username or password"));

        jdbcTemplate.update("UPDATE \"" + schema + "\".app_users SET last_login_at = NOW() WHERE username = ?", username);

        int userId = ((Number) row.get("user_id")).intValue();
        String token = jwtUtil.generateToken(username, schema, firm.getFirmId(), (String) row.get("role"));

        LoginResponse r = new LoginResponse();
        r.setToken(token);
        r.setUserId(userId);
        r.setUsername((String) row.get("username"));
        r.setFullName(nullSafe((String) row.get("full_name")));
        r.setRole((String) row.get("role"));
        r.setIsSuperadmin(false);
        r.setFirmId(firm.getFirmId().intValue());
        r.setFirmName(firm.getFirmName());
        r.setFirmCode(firm.getFirmCode());
        r.setEmail(nullSafe((String) row.get("email")));
        r.setPhone(nullSafe((String) row.get("phone")));
        r.setAccessLevel("ADMIN");
        r.setRequiresFirmSelection(false);
        return ResponseEntity.ok(r);
    }

    private List<Firm> findFirmsWithValidCredentials(String username, String password) {
        List<Firm> matched = new ArrayList<>();
        for (Firm firm : firmRepository.findAll()) {
            if (!Boolean.TRUE.equals(firm.getIsActive())) continue;
            try {
                String sql = "SELECT password_hash, is_active FROM \"" + firm.getSchemaName() +
                             "\".app_users WHERE username = ?";
                List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, username);
                if (!rows.isEmpty()
                        && Boolean.TRUE.equals(rows.get(0).get("is_active"))
                        && passwordEncoder.matches(password, (String) rows.get(0).get("password_hash"))) {
                    matched.add(firm);
                }
            } catch (Exception ignored) {}
        }
        return matched;
    }

    private ResponseEntity<?> multiFirmSelectionResponse(String username, List<Firm> firms) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Firm f : firms) {
            Map<String, Object> item = new HashMap<>();
            item.put("firmId",      f.getFirmId());
            item.put("firmName",    f.getFirmName());
            item.put("firmCode",    f.getFirmCode());
            item.put("accessLevel", "ADMIN");
            item.put("isPrimary",   false);
            list.add(item);
        }
        return ResponseEntity.ok(Map.of(
            "requiresFirmSelection", true,
            "username", username,
            "availableFirms", list
        ));
    }

    private String nullSafe(String s) { return s != null ? s : ""; }
}

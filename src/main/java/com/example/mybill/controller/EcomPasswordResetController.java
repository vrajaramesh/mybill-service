package com.example.mybill.controller;

import com.example.mybill.boutique.payment.AgentEventNotifier;
import com.example.mybill.dto.Firm;
import com.example.mybill.multitenancy.JwtUtil;
import com.example.mybill.repository.FirmRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Website "Forgot password": a 6-digit code is sent by WhatsApp (srisa-agent, approved AUTHENTICATION template)
 * to the phone on the account; with the code she sets a new password and is logged in.
 * Codes: BCrypt-hashed, valid 10 minutes, 5 tries, at most 3 per hour and one per minute per account.
 */
@RestController
@RequestMapping("/api/public")
public class EcomPasswordResetController {

    private static final Logger log = LoggerFactory.getLogger(EcomPasswordResetController.class);
    private static final int CODE_MINUTES = 10, MAX_TRIES = 5, MAX_PER_HOUR = 3, MIN_PASSWORD = 6;

    @Autowired private FirmRepository firmRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private AgentEventNotifier agentNotifier;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final SecureRandom random = new SecureRandom();
    private final Set<String> readySchemas = ConcurrentHashMap.newKeySet();

    /** Body {email}. Always 200 for a well-formed request, so it can't be used to discover accounts. */
    @PostMapping("/{firmCode}/auth/forgot-password")
    public ResponseEntity<?> forgot(@PathVariable String firmCode, @RequestBody Map<String, String> body) {
        Optional<Firm> firm = activeFirm(firmCode);
        if (firm.isEmpty()) return ResponseEntity.notFound().build();
        String s = firm.get().getSchemaName();
        String email = Objects.toString(body.get("email"), "").toLowerCase(Locale.ROOT).trim();
        if (email.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "email is required"));
        ensureTable(s);

        Map<String, Object> generic = Map.of("sent", true,
            "message", "If an account with a phone number exists for this email, we've sent a code to its WhatsApp.");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT customer_id, phone FROM \"" + s + "\".ecom_customers WHERE email = ? AND is_active = TRUE", email);
        if (rows.isEmpty()) return ResponseEntity.ok(generic);
        int customerId = ((Number) rows.get(0).get("customer_id")).intValue();
        String phone = whatsappNumber(Objects.toString(rows.get(0).get("phone"), ""));
        if (phone == null) {
            return ResponseEntity.ok(Map.of("sent", false, "message",
                "There's no mobile number on this account. Please message us on WhatsApp at +91 94916 47691 and we'll help you reset it."));
        }

        Map<String, Object> recent = jdbcTemplate.queryForMap(
            "SELECT COUNT(*) AS n, MAX(created_at) AS last FROM \"" + s + "\".ecom_password_resets " +
            "WHERE customer_id = ? AND created_at > NOW() - INTERVAL '1 hour'", customerId);
        Timestamp last = (Timestamp) recent.get("last");
        if (((Number) recent.get("n")).intValue() >= MAX_PER_HOUR
                || (last != null && last.toInstant().isAfter(java.time.Instant.now().minusSeconds(60)))) {
            return ResponseEntity.status(429).body(Map.of("error", "Too many codes requested. Please wait a few minutes and try again."));
        }

        String code = String.format("%06d", random.nextInt(1_000_000));
        jdbcTemplate.update("INSERT INTO \"" + s + "\".ecom_password_resets (customer_id, code_hash, expires_at) " +
            "VALUES (?, ?, NOW() + INTERVAL '" + CODE_MINUTES + " minutes')", customerId, passwordEncoder.encode(code));

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event", "ecom.reset_code");
        event.put("event_id", "ecom-reset:" + UUID.randomUUID());
        event.put("order_no", "-");
        event.put("customer_phone", phone);
        event.put("code", code);
        if (!agentNotifier.post(event, "password reset code for website customer " + customerId)) {
            return ResponseEntity.status(502).body(Map.of("error",
                "We couldn't send the code right now. Please try again in a minute or message us on WhatsApp."));
        }
        log.info("ECOM_RESET_CODE_SENT schema={} customer={}", s, customerId);
        return ResponseEntity.ok(Map.of("sent", true, "phone_hint", "•••••" + phone.substring(phone.length() - 4),
            "message", "We've sent a 6-digit code to your WhatsApp."));
    }

    /** Body {email, code, password}. On success: new password saved, the code used, and she is logged in. */
    @PostMapping("/{firmCode}/auth/reset-password")
    public ResponseEntity<?> reset(@PathVariable String firmCode, @RequestBody Map<String, String> body) {
        Optional<Firm> firm = activeFirm(firmCode);
        if (firm.isEmpty()) return ResponseEntity.notFound().build();
        String s = firm.get().getSchemaName();
        String email = Objects.toString(body.get("email"), "").toLowerCase(Locale.ROOT).trim();
        String code = Objects.toString(body.get("code"), "").trim();
        String password = Objects.toString(body.get("password"), "");
        if (email.isEmpty() || !code.matches("\\d{6}")) return ResponseEntity.badRequest().body(Map.of("error", "Enter the 6-digit code"));
        if (password.length() < MIN_PASSWORD) {
            return ResponseEntity.badRequest().body(Map.of("error", "Password must be at least " + MIN_PASSWORD + " characters"));
        }
        ensureTable(s);
        Map<String, Object> invalid = Map.of("error", "The code is wrong or has expired. Request a new one.");

        List<Map<String, Object>> customers = jdbcTemplate.queryForList(
            "SELECT customer_id, name, email FROM \"" + s + "\".ecom_customers WHERE email = ? AND is_active = TRUE", email);
        if (customers.isEmpty()) return ResponseEntity.badRequest().body(invalid);
        Map<String, Object> cust = customers.get(0);
        int customerId = ((Number) cust.get("customer_id")).intValue();

        List<Map<String, Object>> resets = jdbcTemplate.queryForList(
            "SELECT id, code_hash, attempts FROM \"" + s + "\".ecom_password_resets " +
            "WHERE customer_id = ? AND used_at IS NULL AND expires_at > NOW() ORDER BY created_at DESC LIMIT 1", customerId);
        if (resets.isEmpty()) return ResponseEntity.badRequest().body(invalid);
        Map<String, Object> r = resets.get(0);
        int id = ((Number) r.get("id")).intValue();
        if (((Number) r.get("attempts")).intValue() >= MAX_TRIES) return ResponseEntity.badRequest().body(invalid);
        if (!passwordEncoder.matches(code, (String) r.get("code_hash"))) {
            jdbcTemplate.update("UPDATE \"" + s + "\".ecom_password_resets SET attempts = attempts + 1 WHERE id = ?", id);
            return ResponseEntity.badRequest().body(invalid);
        }
        // Atomic: only one request can use the code.
        if (jdbcTemplate.update("UPDATE \"" + s + "\".ecom_password_resets SET used_at = NOW() WHERE id = ? AND used_at IS NULL", id) == 0) {
            return ResponseEntity.badRequest().body(invalid);
        }
        jdbcTemplate.update("UPDATE \"" + s + "\".ecom_customers SET password_hash = ? WHERE customer_id = ?",
            passwordEncoder.encode(password), customerId);
        log.info("ECOM_PASSWORD_RESET schema={} customer={}", s, customerId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", jwtUtil.generateToken(email, s, firm.get().getFirmId(), "ECOM_CUSTOMER"));
        result.put("customerId", customerId);
        result.put("name", cust.get("name"));
        result.put("email", cust.get("email"));
        result.put("firmCode", firm.get().getFirmCode());
        return ResponseEntity.ok(result);
    }

    /** E.164 digits for an Indian mobile ("9491647691", "+91 94916 47691" -> 919491647691), else null. */
    static String whatsappNumber(String raw) {
        String d = raw.replaceAll("\\D", "");
        if (d.length() == 12 && d.startsWith("91")) d = d.substring(2);
        if (d.length() == 11 && d.startsWith("0")) d = d.substring(1);
        return d.matches("[6-9]\\d{9}") ? "91" + d : null;
    }

    private Optional<Firm> activeFirm(String firmCode) {
        return firmRepository.findByFirmCode(firmCode.toLowerCase(Locale.ROOT).trim())
            .filter(f -> Boolean.TRUE.equals(f.getIsActive()));
    }

    private void ensureTable(String s) {
        if (readySchemas.contains(s)) return;
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS \"" + s + "\".ecom_password_resets (" +
            "id SERIAL PRIMARY KEY, customer_id INTEGER NOT NULL, code_hash VARCHAR(100) NOT NULL, " +
            "expires_at TIMESTAMP NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, used_at TIMESTAMP, " +
            "created_at TIMESTAMP NOT NULL DEFAULT NOW())");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS ix_ecom_password_resets_customer ON \"" + s +
            "\".ecom_password_resets (customer_id, created_at)");
        readySchemas.add(s);
    }
}

package com.example.mybill.controller;

import com.example.mybill.dto.AppUserPublic;
import com.example.mybill.dto.Firm;
import com.example.mybill.multitenancy.JwtUtil;
import com.example.mybill.repository.AppUserPublicRepository;
import com.example.mybill.service.FirmService;
import com.example.mybill.service.RoleService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/firms")
public class FirmController {

    @Autowired
    private FirmService firmService;

    @Autowired
    private RoleService roleService;

    @Autowired
    private AppUserPublicRepository appUserPublicRepository;

    @Autowired
    private JwtUtil jwtUtil;

    /**
     * Register a new firm — requires superadmin JWT
     */
    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody Map<String, String> body,
                                       HttpServletRequest request) {
        try {
            // Extract superadmin identity from JWT
            String authHeader = request.getHeader("Authorization");
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                return ResponseEntity.status(401).body(Map.of("error", "Authorization required"));
            }
            Claims claims = jwtUtil.extractClaims(authHeader.substring(7));
            String role = claims.get("role", String.class);
            String username = claims.getSubject();

            if (!"SUPERADMIN".equals(role)) {
                return ResponseEntity.status(403).body(Map.of("error", "Only superadmins can create firms"));
            }

            Optional<AppUserPublic> superadminOpt = appUserPublicRepository.findActiveByUsername(username);
            if (superadminOpt.isEmpty()) {
                return ResponseEntity.status(403).body(Map.of("error", "Superadmin not found"));
            }

            String firmName   = body.get("firmName");
            String firmCode   = body.get("firmCode");
            String ownerEmail = body.get("ownerEmail");
            String adminUser  = body.get("adminUsername");
            String adminPass  = body.get("adminPassword");
            String adminName  = body.getOrDefault("adminFullName", "Administrator");

            if (firmName == null || firmCode == null || adminUser == null || adminPass == null) {
                return ResponseEntity.badRequest()
                    .body(Map.of("error", "firmName, firmCode, adminUsername, adminPassword are required"));
            }

            Firm firm = firmService.registerFirm(firmName, firmCode, ownerEmail,
                                                  adminUser, adminPass, adminName,
                                                  superadminOpt.get().getUserId());
            return ResponseEntity.ok(Map.of(
                "firmId",   firm.getFirmId(),
                "firmName", firm.getFirmName(),
                "firmCode", firm.getFirmCode(),
                "message",  "Firm registered successfully"
            ));
        } catch (IllegalAccessError e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("error", "Failed to register firm: " + e.getMessage()));
        }
    }

    /**
     * List all firms — requires superadmin JWT
     */
    @GetMapping
    public ResponseEntity<?> listFirms(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            try {
                Claims claims = jwtUtil.extractClaims(authHeader.substring(7));
                if (!"SUPERADMIN".equals(claims.get("role", String.class))) {
                    return ResponseEntity.status(403).body(Map.of("error", "Superadmin access required"));
                }
            } catch (Exception e) {
                return ResponseEntity.status(401).body(Map.of("error", "Invalid token"));
            }
        } else {
            return ResponseEntity.status(401).body(Map.of("error", "Authorization required"));
        }
        List<Firm> firms = firmService.listFirms();
        return ResponseEntity.ok(firms);
    }
}

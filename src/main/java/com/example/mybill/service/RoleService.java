package com.example.mybill.service;

import com.example.mybill.dto.AppUserPublic;
import com.example.mybill.dto.UserFirmAccess;
import com.example.mybill.dto.Firm;
import com.example.mybill.repository.AppUserPublicRepository;
import com.example.mybill.repository.UserFirmAccessRepository;
import com.example.mybill.repository.FirmRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Optional;

/**
 * Service layer for role-based access control and authorization
 */
@Service
public class RoleService {

    @Autowired
    private AppUserPublicRepository appUserPublicRepository;

    @Autowired
    private UserFirmAccessRepository userFirmAccessRepository;

    @Autowired
    private FirmRepository firmRepository;

    /**
     * Check if a user is a superadmin
     */
    public boolean isSuperadmin(String username) {
        Optional<AppUserPublic> user = appUserPublicRepository.findActiveByUsername(username);
        return user.isPresent() && "SUPERADMIN".equals(user.get().getRole());
    }

    /**
     * Check if a user is a superadmin by ID
     */
    public boolean isSuperadminById(Integer userId) {
        Optional<AppUserPublic> user = appUserPublicRepository.findById(userId);
        return user.isPresent() && "SUPERADMIN".equals(user.get().getRole());
    }

    /**
     * Verify superadmin credentials (username + password)
     */
    public Optional<AppUserPublic> authenticateSuperadmin(String username, String passwordHash) {
        Optional<AppUserPublic> user = appUserPublicRepository.findActiveByUsername(username);
        if (user.isPresent() && user.get().getPasswordHash().equals(passwordHash)) {
            return user;
        }
        return Optional.empty();
    }

    /**
     * Check if user has access to a specific firm
     */
    public boolean hasAccessToFirm(Integer userId, Integer firmId) {
        return userFirmAccessRepository.hasAccessToFirm(userId, firmId);
    }

    /**
     * Check if user has specific access level in a firm
     */
    public boolean hasAccessLevel(Integer userId, Integer firmId, String requiredLevel) {
        Optional<UserFirmAccess> access = userFirmAccessRepository.findActiveAccess(userId, firmId);
        if (access.isEmpty()) {
            return false;
        }

        String userLevel = access.get().getAccessLevel();
        // Check permission hierarchy: ADMIN > MANAGER > VIEWER
        switch (requiredLevel) {
            case "ADMIN":
                return "ADMIN".equals(userLevel);
            case "MANAGER":
                return "ADMIN".equals(userLevel) || "MANAGER".equals(userLevel);
            case "VIEWER":
                return true;  // All levels can view
            default:
                return false;
        }
    }

    /**
     * Get all accessible firms for a user
     */
    public List<UserFirmAccess> getUserFirmAccess(Integer userId) {
        return userFirmAccessRepository.findActiveAccessByUserId(userId);
    }

    /**
     * Get user's primary firm
     */
    public Optional<UserFirmAccess> getPrimaryFirmAccess(Integer userId) {
        return userFirmAccessRepository.findPrimaryFirmAccess(userId);
    }

    /**
     * Count accessible firms for a user
     */
    public long countAccessibleFirms(Integer userId) {
        return userFirmAccessRepository.countActiveAccessByUserId(userId);
    }

    /**
     * Get access record for a user in a specific firm
     */
    public Optional<UserFirmAccess> getAccessRecord(Integer userId, Integer firmId) {
        return userFirmAccessRepository.findActiveAccess(userId, firmId);
    }

    /**
     * Grant firm access to a user (requires ADMIN access in that firm)
     */
    public UserFirmAccess grantFirmAccess(Integer userId, Integer firmId, String accessLevel, Integer adminId) {
        // Verify admin has access to the firm
        if (!hasAccessLevel(adminId, firmId, "ADMIN")) {
            throw new IllegalAccessError("Only admins can grant firm access");
        }

        // Check if access already exists
        Optional<UserFirmAccess> existing = userFirmAccessRepository.findActiveAccess(userId, firmId);
        if (existing.isPresent()) {
            throw new IllegalArgumentException("User already has access to this firm");
        }

        Optional<Firm> firmOpt = firmRepository.findById(firmId.longValue());
        if (firmOpt.isEmpty()) {
            throw new IllegalArgumentException("Firm not found");
        }

        UserFirmAccess access = new UserFirmAccess();
        access.setUserId(userId);
        access.setFirmId(firmId);
        access.setFirmCode(firmOpt.get().getFirmCode());
        access.setAccessLevel(accessLevel);
        access.setIsActive(true);
        access.setIsPrimary(userFirmAccessRepository.countActiveAccessByUserId(userId) == 0);

        return userFirmAccessRepository.save(access);
    }

    /**
     * Revoke firm access from a user
     */
    public void revokeFirmAccess(Integer userId, Integer firmId, Integer adminId) {
        // Verify admin has access to the firm
        if (!hasAccessLevel(adminId, firmId, "ADMIN")) {
            throw new IllegalAccessError("Only admins can revoke firm access");
        }

        Optional<UserFirmAccess> access = userFirmAccessRepository.findActiveAccess(userId, firmId);
        if (access.isPresent()) {
            UserFirmAccess record = access.get();
            record.setIsActive(false);
            userFirmAccessRepository.save(record);
        }
    }

    /**
     * Update user's primary firm
     */
    public void setPrimaryFirm(Integer userId, Integer farmId) {
        List<UserFirmAccess> allAccess = userFirmAccessRepository.findActiveAccessByUserId(userId);
        for (UserFirmAccess access : allAccess) {
            access.setIsPrimary(access.getFirmId().equals(farmId));
            userFirmAccessRepository.save(access);
        }
    }

    /**
     * Register a superadmin user (can only be done by existing superadmin or system setup)
     */
    public AppUserPublic createSuperadmin(String username, String passwordHash, String fullName, String email) {
        // Check if username already exists
        if (appUserPublicRepository.findByUsername(username).isPresent()) {
            throw new IllegalArgumentException("Username already exists");
        }

        AppUserPublic user = new AppUserPublic();
        user.setUsername(username);
        user.setPasswordHash(passwordHash);
        user.setFullName(fullName);
        user.setEmail(email);
        user.setRole("SUPERADMIN");
        user.setIsActive(true);

        return appUserPublicRepository.save(user);
    }
}


package com.example.mybill.repository;

import com.example.mybill.dto.UserFirmAccess;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserFirmAccessRepository extends JpaRepository<UserFirmAccess, Integer> {

    /**
     * Find all firms accessible by a user
     */
    @Query("SELECT u FROM UserFirmAccess u WHERE u.userId = ?1 AND u.isActive = TRUE ORDER BY u.isPrimary DESC")
    List<UserFirmAccess> findActiveAccessByUserId(Integer userId);

    /**
     * Find a specific user-firm access record
     */
    @Query("SELECT u FROM UserFirmAccess u WHERE u.userId = ?1 AND u.firmId = ?2 AND u.isActive = TRUE")
    Optional<UserFirmAccess> findActiveAccess(Integer userId, Integer firmId);

    /**
     * Find primary firm for a user
     */
    @Query("SELECT u FROM UserFirmAccess u WHERE u.userId = ?1 AND u.isPrimary = TRUE AND u.isActive = TRUE")
    Optional<UserFirmAccess> findPrimaryFirmAccess(Integer userId);

    /**
     * Check if user has access to a firm
     */
    @Query("SELECT CASE WHEN COUNT(u) > 0 THEN TRUE ELSE FALSE END FROM UserFirmAccess u WHERE u.userId = ?1 AND u.firmId = ?2 AND u.isActive = TRUE")
    Boolean hasAccessToFirm(Integer userId, Integer firmId);

    /**
     * Find all users with access to a firm
     */
    @Query("SELECT u FROM UserFirmAccess u WHERE u.firmId = ?1 AND u.isActive = TRUE")
    List<UserFirmAccess> findUsersByFirmId(Integer firmId);

    /**
     * Count firms accessible by user
     */
    @Query("SELECT COUNT(u) FROM UserFirmAccess u WHERE u.userId = ?1 AND u.isActive = TRUE")
    long countActiveAccessByUserId(Integer userId);
}


package com.example.mybill.repository;

import com.example.mybill.dto.AppUserPublic;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AppUserPublicRepository extends JpaRepository<AppUserPublic, Integer> {

    Optional<AppUserPublic> findByUsername(String username);

    Optional<AppUserPublic> findByEmail(String email);

    @Query("SELECT u FROM AppUserPublic u WHERE u.username = ?1 AND u.isActive = TRUE")
    Optional<AppUserPublic> findActiveByUsername(String username);
}


package com.example.mybill.repository;

import com.example.mybill.dto.Firm;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FirmRepository extends JpaRepository<Firm, Long> {
    Optional<Firm> findByFirmCode(String firmCode);
    boolean existsByFirmCode(String firmCode);
    boolean existsBySchemaName(String schemaName);
    Optional<Firm> findBySchemaName(String schemaName);
}

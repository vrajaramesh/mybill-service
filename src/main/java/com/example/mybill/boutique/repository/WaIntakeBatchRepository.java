package com.example.mybill.boutique.repository;

import com.example.mybill.boutique.entity.WaIntakeBatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface WaIntakeBatchRepository extends JpaRepository<WaIntakeBatch, Integer> {

    /** The batch still collecting messages from this sender (at most one, see ux_wa_intake_batch_collecting). */
    Optional<WaIntakeBatch> findFirstByFromNumberAndStatus(String fromNumber, String status);

    /** Collecting batches with no new message since `before` (to be closed). */
    List<WaIntakeBatch> findByStatusAndUpdatedAtBefore(String status, LocalDateTime before);

    List<WaIntakeBatch> findTop50ByOrderByCreatedAtDesc();
}

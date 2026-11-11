package com.example.mybill.boutique.repository;

import com.example.mybill.boutique.entity.WaIntakeBatch;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface WaIntakeBatchRepository extends JpaRepository<WaIntakeBatch, Integer> {

    /** The batch still collecting messages from this sender (at most one, see ux_wa_intake_batch_collecting). */
    Optional<WaIntakeBatch> findFirstByFromNumberAndStatus(String fromNumber, String status);

    /** Collecting batches with no new message since `before` (to be closed). */
    List<WaIntakeBatch> findByStatusAndUpdatedAtBefore(String status, LocalDateTime before);

    List<WaIntakeBatch> findTop50ByOrderByCreatedAtDesc();

    /** Posts still waiting for the owner's supplier choice, asked before `before` and not reminded yet. */
    List<WaIntakeBatch> findByStatusAndSupplierAskedAtBeforeAndRemindedAtIsNull(String status, LocalDateTime before);

    /** Row lock: photos of one post arrive in parallel tasks and must get distinct numbers. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM WaIntakeBatch b WHERE b.batchId = :id")
    Optional<WaIntakeBatch> lockById(@Param("id") Integer id);
}

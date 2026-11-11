package com.example.mybill.boutique.repository;

import com.example.mybill.boutique.entity.WaIntakeMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WaIntakeMessageRepository extends JpaRepository<WaIntakeMessage, Integer> {

    Optional<WaIntakeMessage> findByWaMessageId(String waMessageId);

    /** A batch's messages in arrival order (the post's text, then its photos). */
    List<WaIntakeMessage> findByBatchIdOrderByReceivedAtAscMessageIdAsc(Integer batchId);
}

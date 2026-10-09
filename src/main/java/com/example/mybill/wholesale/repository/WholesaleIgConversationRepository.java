package com.example.mybill.wholesale.repository;

import com.example.mybill.wholesale.entity.WholesaleIgConversation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface WholesaleIgConversationRepository extends JpaRepository<WholesaleIgConversation, Integer> {

    Optional<WholesaleIgConversation> findByInstagramUserId(String instagramUserId);

    /** Serialises takeover / release / AI reply decisions per conversation. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM WholesaleIgConversation c WHERE c.conversationId = :id")
    Optional<WholesaleIgConversation> findByIdForUpdate(@Param("id") Integer id);

    @EntityGraph(attributePaths = {"customer"})
    @Query("SELECT c FROM WholesaleIgConversation c ORDER BY c.needsAttention DESC, c.lastMessageAt DESC NULLS LAST, c.conversationId DESC")
    List<WholesaleIgConversation> findAllForInbox();

    /** First conversation for an Instagram user; concurrent first messages collapse onto one row. */
    @Modifying
    @Query(value = """
        INSERT INTO wholesale_ig_conversations (instagram_user_id) VALUES (:igUserId)
        ON CONFLICT (instagram_user_id) DO NOTHING""", nativeQuery = true)
    int insertIfAbsent(@Param("igUserId") String igUserId);
}

package com.example.mybill.wholesale.repository;

import com.example.mybill.wholesale.entity.WholesaleIgMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface WholesaleIgMessageRepository extends JpaRepository<WholesaleIgMessage, Long> {

    boolean existsByInstagramMessageId(String instagramMessageId);

    Optional<WholesaleIgMessage> findByInstagramMessageId(String instagramMessageId);

    List<WholesaleIgMessage> findByConversation_ConversationIdOrderByMessageAtAscMessageIdAsc(Integer conversationId);

    /** Newest first; the assistant reverses it to read the conversation in order. */
    @Query("""
        SELECT m FROM WholesaleIgMessage m
         WHERE m.conversation.conversationId = :conversationId AND m.messageId < :beforeId
         ORDER BY m.messageAt DESC, m.messageId DESC""")
    List<WholesaleIgMessage> findHistory(@Param("conversationId") Integer conversationId,
                                         @Param("beforeId") Long beforeId, Pageable page);

    /**
     * An outbound message this app sent whose Instagram echo arrived before the send call returned its id
     * (same conversation and text, still without an Instagram message id).
     */
    @Query("""
        SELECT m FROM WholesaleIgMessage m
         WHERE m.conversation.conversationId = :conversationId
           AND m.direction = :direction
           AND m.instagramMessageId IS NULL
           AND m.messageText = :text
           AND m.createdAt >= :since
         ORDER BY m.messageId ASC""")
    List<WholesaleIgMessage> findUnconfirmedOutbound(@Param("conversationId") Integer conversationId,
                                                     @Param("direction") WholesaleIgMessage.Direction direction,
                                                     @Param("text") String text,
                                                     @Param("since") LocalDateTime since);
}

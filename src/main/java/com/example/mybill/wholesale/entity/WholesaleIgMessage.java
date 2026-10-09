package com.example.mybill.wholesale.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * One Instagram message. Inbound rows also keep what the AI understood (intent, extracted), the business facts the
 * reply was built from, the generated reply and the processing status, so every automatic answer can be audited.
 */
@Entity
@Table(name = "wholesale_ig_messages")
public class WholesaleIgMessage {

    public enum Direction { INBOUND, OUTBOUND }

    public enum SenderType { CUSTOMER, AI, STAFF }

    /**
     * Inbound: RECEIVED → PROCESSING → REPLIED | NEEDS_HUMAN | HUMAN_TAKEOVER | AI_DISABLED | IGNORED | FAILED.
     * Outbound: PENDING → SENT | FAILED.
     */
    public enum Status { RECEIVED, PROCESSING, REPLIED, NEEDS_HUMAN, HUMAN_TAKEOVER, AI_DISABLED, IGNORED, FAILED, PENDING, SENT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "message_id")
    private Long messageId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false, updatable = false)
    private WholesaleIgConversation conversation;

    @Column(name = "instagram_message_id", length = 255) private String instagramMessageId;
    @Enumerated(EnumType.STRING) @Column(name = "direction", nullable = false, length = 10) private Direction direction;
    @Enumerated(EnumType.STRING) @Column(name = "sender_type", nullable = false, length = 10) private SenderType senderType;
    @Column(name = "message_text") private String messageText;

    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "attachments", columnDefinition = "jsonb")
    private List<Map<String, Object>> attachments;

    @Column(name = "intent", length = 40) private String intent;

    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "extracted", columnDefinition = "jsonb")
    private Map<String, Object> extracted;

    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "facts", columnDefinition = "jsonb")
    private Map<String, Object> facts;

    @Column(name = "ai_response") private String aiResponse;
    @Column(name = "reply_message_id") private Long replyMessageId;
    @Enumerated(EnumType.STRING) @Column(name = "status", nullable = false, length = 20) private Status status;
    @Column(name = "error_message", length = 1000) private String errorMessage;
    @Column(name = "sent_by", length = 100) private String sentBy;
    @Column(name = "message_at", nullable = false) private LocalDateTime messageAt;
    @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
        if (messageAt == null) messageAt = createdAt;
    }

    public void fail(String error) {
        status = Status.FAILED;
        errorMessage = error == null ? "Unknown error" : error.length() > 1000 ? error.substring(0, 1000) : error;
    }

    public Long getMessageId() { return messageId; }
    public WholesaleIgConversation getConversation() { return conversation; }
    public void setConversation(WholesaleIgConversation conversation) { this.conversation = conversation; }
    public String getInstagramMessageId() { return instagramMessageId; }
    public void setInstagramMessageId(String instagramMessageId) { this.instagramMessageId = instagramMessageId; }
    public Direction getDirection() { return direction; }
    public void setDirection(Direction direction) { this.direction = direction; }
    public SenderType getSenderType() { return senderType; }
    public void setSenderType(SenderType senderType) { this.senderType = senderType; }
    public String getMessageText() { return messageText; }
    public void setMessageText(String messageText) { this.messageText = messageText; }
    public List<Map<String, Object>> getAttachments() { return attachments; }
    public void setAttachments(List<Map<String, Object>> attachments) { this.attachments = attachments; }
    public String getIntent() { return intent; }
    public void setIntent(String intent) { this.intent = intent; }
    public Map<String, Object> getExtracted() { return extracted; }
    public void setExtracted(Map<String, Object> extracted) { this.extracted = extracted; }
    public Map<String, Object> getFacts() { return facts; }
    public void setFacts(Map<String, Object> facts) { this.facts = facts; }
    public String getAiResponse() { return aiResponse; }
    public void setAiResponse(String aiResponse) { this.aiResponse = aiResponse; }
    public Long getReplyMessageId() { return replyMessageId; }
    public void setReplyMessageId(Long replyMessageId) { this.replyMessageId = replyMessageId; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getSentBy() { return sentBy; }
    public void setSentBy(String sentBy) { this.sentBy = sentBy; }
    public LocalDateTime getMessageAt() { return messageAt; }
    public void setMessageAt(LocalDateTime messageAt) { this.messageAt = messageAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}

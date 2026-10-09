package com.example.mybill.wholesale.entity;

import jakarta.persistence.*;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

/** One Instagram customer (Instagram-scoped user id) and their thread with the business. */
@Entity
@Table(name = "wholesale_ig_conversations")
public class WholesaleIgConversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "conversation_id")
    private Integer conversationId;

    @Column(name = "instagram_user_id", nullable = false, length = 64, updatable = false) private String instagramUserId;
    @Column(name = "instagram_username", length = 100) private String instagramUsername;
    @Column(name = "display_name", length = 200) private String displayName;

    /** Wholesale customer this Instagram user was mapped to by staff (optional). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "wholesale_customer_id")
    private WholesaleCustomer customer;

    /** false = human takeover: the AI stores what it understood but never replies. */
    @Column(name = "ai_enabled", nullable = false) private Boolean aiEnabled = true;
    @Column(name = "takeover_by", length = 100) private String takeoverBy;
    @Column(name = "takeover_at") private LocalDateTime takeoverAt;
    @Column(name = "needs_attention", nullable = false) private Boolean needsAttention = false;
    @Column(name = "attention_reason", length = 255) private String attentionReason;
    @Column(name = "unread_count", nullable = false) private Integer unreadCount = 0;

    /** Product discussed last, so follow-ups like "how much for 100 meters?" resolve to it. */
    @Column(name = "last_product_id") private Integer lastProductId;
    /** Assistant context: current product / quantity / last quotation, so customers need not repeat themselves. */
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "assistant_context", columnDefinition = "jsonb")
    private Map<String, Object> assistantContext;
    @Column(name = "last_message_text", length = 300) private String lastMessageText;
    @Column(name = "last_message_at") private LocalDateTime lastMessageAt;
    @Column(name = "last_inbound_at") private LocalDateTime lastInboundAt;
    @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /** Records a new message in the thread summary. */
    public void touch(String text, LocalDateTime at, boolean inbound) {
        lastMessageText = text == null ? null : text.length() > 300 ? text.substring(0, 297) + "..." : text;
        if (lastMessageAt == null || at.isAfter(lastMessageAt)) lastMessageAt = at;
        if (inbound) {
            if (lastInboundAt == null || at.isAfter(lastInboundAt)) lastInboundAt = at;
            unreadCount = unreadCount + 1;
        }
    }

    public void flag(String reason) {
        needsAttention = true;
        attentionReason = reason == null ? null : reason.length() > 255 ? reason.substring(0, 255) : reason;
    }

    public Integer getConversationId() { return conversationId; }
    public String getInstagramUserId() { return instagramUserId; }
    public void setInstagramUserId(String instagramUserId) { this.instagramUserId = instagramUserId; }
    public String getInstagramUsername() { return instagramUsername; }
    public void setInstagramUsername(String instagramUsername) { this.instagramUsername = instagramUsername; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public WholesaleCustomer getCustomer() { return customer; }
    public void setCustomer(WholesaleCustomer customer) { this.customer = customer; }
    public Boolean getAiEnabled() { return aiEnabled; }
    public void setAiEnabled(Boolean aiEnabled) { this.aiEnabled = aiEnabled; }
    public String getTakeoverBy() { return takeoverBy; }
    public void setTakeoverBy(String takeoverBy) { this.takeoverBy = takeoverBy; }
    public LocalDateTime getTakeoverAt() { return takeoverAt; }
    public void setTakeoverAt(LocalDateTime takeoverAt) { this.takeoverAt = takeoverAt; }
    public Boolean getNeedsAttention() { return needsAttention; }
    public void setNeedsAttention(Boolean needsAttention) { this.needsAttention = needsAttention; }
    public String getAttentionReason() { return attentionReason; }
    public void setAttentionReason(String attentionReason) { this.attentionReason = attentionReason; }
    public Integer getUnreadCount() { return unreadCount; }
    public void setUnreadCount(Integer unreadCount) { this.unreadCount = unreadCount; }
    public Integer getLastProductId() { return lastProductId; }
    public void setLastProductId(Integer lastProductId) { this.lastProductId = lastProductId; }
    public Map<String, Object> getAssistantContext() { return assistantContext; }
    public void setAssistantContext(Map<String, Object> assistantContext) { this.assistantContext = assistantContext; }
    public String getLastMessageText() { return lastMessageText; }
    public LocalDateTime getLastMessageAt() { return lastMessageAt; }
    public LocalDateTime getLastInboundAt() { return lastInboundAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}

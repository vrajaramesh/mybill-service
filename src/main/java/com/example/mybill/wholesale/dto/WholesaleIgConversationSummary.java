package com.example.mybill.wholesale.dto;

import java.time.LocalDateTime;

/** Inbox row. replyWindowOpen = the customer wrote within the last 24 hours (Instagram only allows replies then). */
public record WholesaleIgConversationSummary(
    Integer conversationId,
    String instagramUserId,
    String instagramUsername,
    String displayName,
    Integer customerId,
    String customerName,
    Boolean aiEnabled,
    String takeoverBy,
    LocalDateTime takeoverAt,
    Boolean needsAttention,
    String attentionReason,
    Integer unreadCount,
    String lastMessageText,
    LocalDateTime lastMessageAt,
    LocalDateTime lastInboundAt,
    Boolean replyWindowOpen
) {}

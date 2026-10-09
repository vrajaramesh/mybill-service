package com.example.mybill.wholesale.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record WholesaleIgMessageResponse(
    Long messageId,
    String direction,
    String senderType,
    String messageText,
    List<Map<String, Object>> attachments,
    String intent,
    Map<String, Object> extracted,
    Map<String, Object> facts,
    String aiResponse,
    Long replyMessageId,
    String status,
    String errorMessage,
    String sentBy,
    LocalDateTime messageAt
) {}

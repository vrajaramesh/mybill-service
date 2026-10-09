package com.example.mybill.wholesale.dto;

import java.time.LocalDateTime;

/** Instagram messaging settings without secrets; webhookPath is relative to the API host. */
public record WholesaleInstagramSettingsResponse(
    Boolean isEnabled,
    Boolean autoReplyEnabled,
    String instagramAccountId,
    String apiBaseUrl,
    Boolean accessTokenSet,
    Boolean appSecretSet,
    String verifyToken,
    String handoffMessage,
    String webhookPath,
    Boolean aiConfigured,
    LocalDateTime updatedAt,
    String updatedBy
) {}

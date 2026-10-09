package com.example.mybill.wholesale.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Instagram messaging settings. accessToken / appSecret: null = keep the stored value, "" = clear it
 * (they are never returned by the API).
 */
public record WholesaleInstagramSettingsRequest(
    Boolean isEnabled,
    Boolean autoReplyEnabled,
    @Size(max = 64) String instagramAccountId,
    @Size(max = 255) String apiBaseUrl,
    @Size(max = 2000) String accessToken,
    @Size(max = 255) String appSecret,
    @Size(max = 255) String verifyToken,
    @NotBlank @Size(max = 500) String handoffMessage
) {}

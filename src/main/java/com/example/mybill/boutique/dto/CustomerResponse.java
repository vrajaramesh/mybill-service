package com.example.mybill.boutique.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.time.LocalDateTime;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CustomerResponse(Integer customerId, String name, String phoneE164, String instagramUserId,
                               String source, boolean created,
                               /** Set when this upsert merged another customer into this one (that id no longer exists). */
                               Integer mergedCustomerId,
                               Boolean whatsappUpdatesConsent, LocalDateTime consentAt,
                               LocalDateTime createdAt, LocalDateTime updatedAt) {}

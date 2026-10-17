package com.example.mybill.boutique.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.time.LocalDateTime;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CustomerResponse(Integer customerId, String name, String phoneE164, String instagramUserId,
                               String source, boolean created, LocalDateTime createdAt, LocalDateTime updatedAt) {}

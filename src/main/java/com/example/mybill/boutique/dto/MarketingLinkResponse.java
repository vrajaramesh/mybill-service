package com.example.mybill.boutique.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.time.LocalDateTime;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record MarketingLinkResponse(
    Integer linkId,
    String sourceType,
    String sourceId,
    String designCode,
    String designName,
    String label,
    boolean active,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {}

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
    String permalink,
    /** MANUAL (staff, never changed by the Instagram sync) or AUTO (sync-ig-media). */
    String createdBy,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    /** Set when the link promotes a collection (design_code is then null; design_name holds the collection name). */
    String collectionCode
) {}

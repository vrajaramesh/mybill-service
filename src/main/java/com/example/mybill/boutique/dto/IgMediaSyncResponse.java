package com.example.mybill.boutique.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;

/**
 * Result of POST /api/agent/marketing-links/sync-ig-media. Per item, result is created | updated | skipped and
 * reason (for skipped) one of invalid_media_id, no_design_code, design_not_found, manual_link, unchanged,
 * permalink_in_use.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record IgMediaSyncResponse(int created, int updated, int skipped, List<Item> items) {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Item(String mediaId, String result, String designCode, String reason) {}
}

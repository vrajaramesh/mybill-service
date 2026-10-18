package com.example.mybill.boutique.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** One Instagram post from the Graph API (/me/media fields). media_type/timestamp are accepted but not stored. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record IgMediaItem(String mediaId, String permalink, String caption, String mediaType, String timestamp) {}

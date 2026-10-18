package com.example.mybill.boutique.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

/** A marketing event reported by the agent. Only conversation_started exists today. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record MarketingEventRequest(
    @Pattern(regexp = "^conversation_started$", message = "event_type must be conversation_started")
    String eventType,

    @NotBlank(message = "source_type is required")
    @Pattern(regexp = "^(AD|IG_MEDIA)$", message = "source_type must be AD or IG_MEDIA")
    String sourceType,

    @NotBlank(message = "source_id is required")
    @Pattern(regexp = "^[0-9]{1,40}$", message = "source_id must be numeric")
    String sourceId,

    @Size(max = 500, message = "ctwa_clid must be at most 500 characters")
    String ctwaClid,

    Integer customerId,

    /** ISO-8601 with offset, e.g. 2026-10-18T10:15:00+05:30. Defaults to now. */
    OffsetDateTime occurredAt
) {}

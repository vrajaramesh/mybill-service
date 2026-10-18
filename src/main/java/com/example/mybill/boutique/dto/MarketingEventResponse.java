package com.example.mybill.boutique.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** created = false when an event with the same ctwa_clid was already recorded. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record MarketingEventResponse(Long eventId, boolean created) {}

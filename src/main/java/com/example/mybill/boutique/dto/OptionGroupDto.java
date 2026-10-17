package com.example.mybill.boutique.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;

/** All options of one group (e.g. SLEEVE). The customer may pick at most one per group. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OptionGroupDto(String group, List<OptionDto> options) {}

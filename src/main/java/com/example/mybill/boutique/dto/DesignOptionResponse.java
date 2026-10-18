package com.example.mybill.boutique.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** Design picker entry (designs with a code only). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DesignOptionResponse(String code, String name, boolean active) {}

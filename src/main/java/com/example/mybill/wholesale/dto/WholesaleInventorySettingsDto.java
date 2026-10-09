package com.example.mybill.wholesale.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

/** Wholesale inventory rules. allowNegativeStock = false (default) refuses movements that would go below zero. */
public record WholesaleInventorySettingsDto(
    @NotNull(message = "allowNegativeStock is required") Boolean allowNegativeStock,
    LocalDateTime updatedAt,
    String updatedBy
) {}

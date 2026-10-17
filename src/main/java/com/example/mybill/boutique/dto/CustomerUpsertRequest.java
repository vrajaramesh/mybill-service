package com.example.mybill.boutique.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Identify a customer by phone and/or Instagram id; at least one is required. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CustomerUpsertRequest(
    @Pattern(regexp = "^\\+[1-9][0-9]{7,14}$", message = "phone_e164 must be in E.164 format, e.g. +919876543210")
    String phoneE164,

    @Size(max = 64, message = "instagram_user_id must be at most 64 characters")
    String instagramUserId,

    @Size(max = 100, message = "name must be at most 100 characters")
    String name
) {
    @AssertTrue(message = "phone_e164 or instagram_user_id is required")
    public boolean isIdentified() {
        return (phoneE164 != null && !phoneE164.isBlank()) || (instagramUserId != null && !instagramUserId.isBlank());
    }
}

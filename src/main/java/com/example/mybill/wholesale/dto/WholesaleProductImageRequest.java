package com.example.mybill.wholesale.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record WholesaleProductImageRequest(
    @NotBlank(message = "Image URL is required")
    @Size(max = 500, message = "Image URL must be at most 500 characters")
    @Pattern(regexp = "^https://.+", message = "Image URL must start with https://")
    String imageUrl,

    @Size(max = 255)
    String publicId,

    Integer sortOrder
) {}

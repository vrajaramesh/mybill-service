package com.example.mybill.wholesale.dto;

import java.time.LocalDateTime;

public record WholesaleProductImageResponse(
    Integer imageId,
    String imageUrl,
    String publicId,
    Integer sortOrder,
    LocalDateTime createdAt
) {}

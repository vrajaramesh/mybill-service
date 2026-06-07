package com.example.mybill.service;

/**
 * Carries a generated photo URL together with its metadata,
 * passed from AIPhotoController to ImageEmbeddingService.
 */
public record PhotoResult(
    String url,
    String garmentType,
    String occasion,
    String description
) {}

package com.example.mybill.service;

public interface ImageGenerationProvider {

    /** Flat-lay from an existing Cloudinary/remote URL. */
    String generateFlatLay(String referenceUrl, String prompt);

    /** Flat-lay directly from uploaded image bytes — no intermediate storage. */
    String generateFlatLayFromBytes(byte[] imageBytes, String contentType, String prompt);

    /** Garment photo from an existing Cloudinary/remote URL. */
    String generateGarment(String referenceUrl, String prompt);

    /** Garment photo directly from uploaded image bytes — no intermediate storage. */
    String generateGarmentFromBytes(byte[] imageBytes, String contentType, String prompt);

    /** Text-only fallback — used when no reference image is available. */
    String generateTextOnly(String prompt, String quality);
}

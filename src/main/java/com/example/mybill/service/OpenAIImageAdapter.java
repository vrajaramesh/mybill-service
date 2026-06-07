package com.example.mybill.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class OpenAIImageAdapter implements ImageGenerationProvider {

    @Autowired
    private OpenAIService openAIService;

    @Override
    public String generateFlatLay(String referenceUrl, String prompt) {
        // URL-based fallback (existing DB image) — still uses /v1/images/edits
        if (referenceUrl != null) return openAIService.generateImageWithReference(referenceUrl, prompt, "high");
        return openAIService.generateHdPhoto(prompt);
    }

    @Override
    public String generateFlatLayFromBytes(byte[] imageBytes, String contentType, String prompt) {
        return openAIService.generateImageWithBytes(imageBytes, contentType, prompt, "high");
    }

    @Override
    public String generateGarment(String referenceUrl, String prompt) {
        // URL-based fallback (existing DB image) — still uses /v1/images/edits
        if (referenceUrl != null) return openAIService.generateImageWithReference(referenceUrl, prompt, "high");
        return openAIService.generateDalleImage(prompt);
    }

    @Override
    public String generateGarmentFromBytes(byte[] imageBytes, String contentType, String prompt) {
        return openAIService.generateImageWithBytes(imageBytes, contentType, prompt, "high");
    }

    @Override
    public String generateTextOnly(String prompt, String quality) {
        return "high".equals(quality) ? openAIService.generateHdPhoto(prompt) : openAIService.generateDalleImage(prompt);
    }
}

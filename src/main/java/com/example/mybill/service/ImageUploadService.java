package com.example.mybill.service;

public interface ImageUploadService {
    String uploadBase64(String base64, String mimeType);
    String uploadFromUrl(String imageUrl);
}
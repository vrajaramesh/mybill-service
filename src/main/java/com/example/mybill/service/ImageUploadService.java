package com.example.mybill.service;

public interface ImageUploadService {
    String uploadBase64(String base64, String mimeType);
    String uploadFromUrl(String imageUrl);
    /** Upload raw bytes (e.g. from a MultipartFile). Returns the public URL. */
    String uploadFile(byte[] bytes, String mimeType);
}
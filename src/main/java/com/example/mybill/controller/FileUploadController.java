package com.example.mybill.controller;

import com.example.mybill.service.ImageUploadService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/upload")
public class FileUploadController {

    @Autowired
    private ImageUploadService imageUploadService;

    @PostMapping("/image")
    public ResponseEntity<?> uploadImage(@RequestParam("file") MultipartFile file) {
        try {
            byte[] bytes = file.getBytes();
            String mimeType = file.getContentType() != null ? file.getContentType() : "image/jpeg";
            String url = imageUploadService.uploadFile(bytes, mimeType);
            String publicId = derivePublicId(url);
            return ResponseEntity.ok(Map.of("secure_url", url, "url", url, "public_id", publicId));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // Extract a stable identifier from the URL (path without extension).
    // GCS: https://storage.googleapis.com/bucket/product-uploads/uuid.jpg -> product-uploads/uuid
    // Cloudinary: https://res.cloudinary.com/.../product-uploads/uuid.jpg -> uuid
    private String derivePublicId(String url) {
        if (url == null) return "";
        int lastSlash = url.lastIndexOf('/');
        int lastDot = url.lastIndexOf('.');
        String filename = (lastDot > lastSlash) ? url.substring(lastSlash + 1, lastDot) : url.substring(lastSlash + 1);
        // For GCS URLs include the folder segment for uniqueness
        if (url.contains("storage.googleapis.com")) {
            int bucketEnd = url.indexOf('/', "https://storage.googleapis.com/".length());
            if (bucketEnd > 0) {
                String path = url.substring(bucketEnd + 1);
                int dotIdx = path.lastIndexOf('.');
                return dotIdx > 0 ? path.substring(0, dotIdx) : path;
            }
        }
        return filename;
    }
}

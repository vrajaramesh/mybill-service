package com.example.mybill.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/upload")
public class FileUploadController {

    @Value("${cloudinary.cloud-name:}")
    private String cloudName;

    @Value("${cloudinary.upload-preset:}")
    private String uploadPreset;

    @Value("${cloudinary.api.url:https://api.cloudinary.com/v1_1}")
    private String cloudinaryApiUrl;

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30)).build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostMapping("/image")
    public ResponseEntity<?> uploadImage(@RequestParam("file") MultipartFile file) {
        try {
            String boundary = "----FormBoundary" + UUID.randomUUID().toString().replace("-", "");
            String filename  = sanitize(file.getOriginalFilename());
            String mimeType  = file.getContentType() != null ? file.getContentType() : "image/jpeg";

            // Build multipart body manually so we control the part names exactly
            byte[] fileBytes = file.getBytes();
            byte[] body      = buildMultipart(boundary, filename, mimeType, fileBytes);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(cloudinaryApiUrl + "/" + cloudName + "/image/upload"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .timeout(Duration.ofSeconds(60))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Upload failed (" + response.statusCode() + "): " + response.body()));
            }

            JsonNode json = objectMapper.readTree(response.body());
            String secureUrl = json.path("secure_url").asText();
            String publicId  = json.path("public_id").asText();
            return ResponseEntity.ok(Map.of("secure_url", secureUrl, "url", secureUrl, "public_id", publicId));

        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    private byte[] buildMultipart(String boundary, String filename, String mimeType, byte[] fileBytes) {
        String CRLF = "\r\n";
        String delimiter = "--" + boundary + CRLF;
        String closeDelimiter = "--" + boundary + "--" + CRLF;

        StringBuilder header = new StringBuilder();
        header.append(delimiter)
              .append("Content-Disposition: form-data; name=\"upload_preset\"").append(CRLF)
              .append(CRLF)
              .append(uploadPreset).append(CRLF)
              .append(delimiter)
              .append("Content-Disposition: form-data; name=\"file\"; filename=\"").append(filename).append("\"").append(CRLF)
              .append("Content-Type: ").append(mimeType).append(CRLF)
              .append(CRLF);

        byte[] headerBytes = header.toString().getBytes(StandardCharsets.UTF_8);
        byte[] footerBytes = (CRLF + closeDelimiter).getBytes(StandardCharsets.UTF_8);

        byte[] result = new byte[headerBytes.length + fileBytes.length + footerBytes.length];
        System.arraycopy(headerBytes, 0, result, 0, headerBytes.length);
        System.arraycopy(fileBytes,   0, result, headerBytes.length, fileBytes.length);
        System.arraycopy(footerBytes, 0, result, headerBytes.length + fileBytes.length, footerBytes.length);
        return result;
    }

    private String sanitize(String name) {
        if (name == null || name.isBlank()) return "upload.jpg";
        // strip path separators that would trigger the Cloudinary display-name error
        return name.replaceAll("[/\\\\]", "_");
    }
}

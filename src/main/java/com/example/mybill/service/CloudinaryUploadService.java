package com.example.mybill.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Service
@Profile("local")
public class CloudinaryUploadService implements ImageUploadService {

    @Value("${cloudinary.cloud-name}")
    private String cloudName;

    @Value("${cloudinary.upload-preset}")
    private String uploadPreset;

    @Value("${cloudinary.api.url}")
    private String cloudinaryApiUrl;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public String uploadBase64(String base64, String mimeType) {
        try {
            String dataUrl = "data:" + (mimeType != null ? mimeType : "image/jpeg") + ";base64," + base64;
            String body = "file=" + URLEncoder.encode(dataUrl, StandardCharsets.UTF_8) +
                          "&upload_preset=" + URLEncoder.encode(uploadPreset, StandardCharsets.UTF_8) +
                          "&folder=chat-images";

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(cloudinaryApiUrl + "/" + cloudName + "/image/upload"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(30))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200)
                throw new RuntimeException("Upload failed (" + response.statusCode() + "): " + response.body());

            JsonNode json = objectMapper.readTree(response.body());
            return json.path("secure_url").asText();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Cloudinary base64 upload error: " + e.getMessage(), e);
        }
    }

    public String uploadFromUrl(String imageUrl) {
        try {
            String body = "file=" + URLEncoder.encode(imageUrl, StandardCharsets.UTF_8) +
                          "&upload_preset=" + URLEncoder.encode(uploadPreset, StandardCharsets.UTF_8) +
                          "&folder=ai-generated";

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(cloudinaryApiUrl + "/" + cloudName + "/image/upload"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(60))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200)
                throw new RuntimeException("Cloudinary upload failed (" + response.statusCode() + "): " + response.body());

            JsonNode json = objectMapper.readTree(response.body());
            return json.path("secure_url").asText();

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Cloudinary upload error: " + e.getMessage(), e);
        }
    }
}

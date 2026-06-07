package com.example.mybill.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Fal.ai FLUX Dev image generation.
 * Active when image.generation.provider=falaai in application.properties.
 *
 * Flat-lay   → strength 0.65  (preserve fabric closely, beautify background)
 * Garment    → strength 0.85  (allow garment shaping while keeping fabric feel)
 * Text-only  → FLUX text-to-image (no reference)
 */
@Service
@ConditionalOnProperty(name = "image.generation.provider", havingValue = "falaai")
@Primary
public class FalAIService implements ImageGenerationProvider {

    private static final String IMG2IMG_PATH = "/fal-ai/flux/dev/image-to-image";
    private static final String TXT2IMG_PATH = "/fal-ai/flux/dev";

    @Value("${falaai.api.key}")
    private String apiKey;

    @Value("${falaai.api.url:https://fal.run}")
    private String baseUrl;

    @Autowired
    private ImageUploadService imageUploadService;

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String generateFlatLay(String referenceUrl, String prompt) {
        return referenceUrl != null ? img2img(referenceUrl, prompt, 0.65, 50) : txt2img(prompt, 50);
    }

    @Override
    public String generateFlatLayFromBytes(byte[] imageBytes, String contentType, String prompt) {
        // Fal.ai requires a URL — upload to Cloudinary first to get one
        String base64 = java.util.Base64.getEncoder().encodeToString(imageBytes);
        String url = imageUploadService.uploadBase64(base64, contentType != null ? contentType : "image/jpeg");
        return img2img(url, prompt, 0.65, 50);
    }

    @Override
    public String generateGarment(String referenceUrl, String prompt) {
        return referenceUrl != null ? img2img(referenceUrl, prompt, 0.85, 28) : txt2img(prompt, 28);
    }

    @Override
    public String generateGarmentFromBytes(byte[] imageBytes, String contentType, String prompt) {
        String base64 = java.util.Base64.getEncoder().encodeToString(imageBytes);
        String url = imageUploadService.uploadBase64(base64, contentType != null ? contentType : "image/jpeg");
        return img2img(url, prompt, 0.85, 28);
    }

    @Override
    public String generateTextOnly(String prompt, String quality) {
        return txt2img(prompt, "high".equals(quality) ? 50 : 28);
    }

    private String img2img(String referenceUrl, String prompt, double strength, int steps) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("image_url", referenceUrl);
            body.put("prompt", prompt);
            body.put("strength", strength);
            body.put("num_images", 1);
            body.put("image_size", "square_hd");
            body.put("num_inference_steps", steps);
            body.put("guidance_scale", 3.5);
            body.put("enable_safety_checker", false);

            String falUrl = callFal(IMG2IMG_PATH, body);
            return imageUploadService.uploadFromUrl(falUrl);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Fal.ai img2img failed: " + e.getMessage(), e);
        }
    }

    private String txt2img(String prompt, int steps) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("prompt", prompt);
            body.put("num_images", 1);
            body.put("image_size", "square_hd");
            body.put("num_inference_steps", steps);
            body.put("guidance_scale", 3.5);
            body.put("enable_safety_checker", false);

            String falUrl = callFal(TXT2IMG_PATH, body);
            return imageUploadService.uploadFromUrl(falUrl);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Fal.ai txt2img failed: " + e.getMessage(), e);
        }
    }

    private String callFal(String path, ObjectNode body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .header("Content-Type", "application/json")
            .header("Authorization", "Key " + apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
            .timeout(Duration.ofSeconds(180))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200)
            throw new RuntimeException("Fal.ai error " + response.statusCode() + ": " + response.body());

        JsonNode json = objectMapper.readTree(response.body());
        JsonNode images = json.path("images");
        if (!images.isArray() || images.isEmpty())
            throw new RuntimeException("Fal.ai returned no images: " + response.body());

        return images.get(0).path("url").asText();
    }
}

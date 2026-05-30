package com.example.mybill.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Service
public class OpenAIService {

    @Value("${openai.api.key}")
    private String apiKey;

    @Value("${openai.vision.url}")
    private String visionUrl;

    @Value("${openai.image.url}")
    private String imageUrl;

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    public String analyzeProductImage(String base64Image, String mimeType,
                                       String productName, String category) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", "gpt-4o");
            body.put("max_tokens", 600);

            ArrayNode messages = body.putArray("messages");
            ObjectNode message  = messages.addObject();
            message.put("role", "user");

            ArrayNode content = message.putArray("content");

            ObjectNode img = content.addObject();
            img.put("type", "image_url");
            ObjectNode imgUrl = img.putObject("image_url");
            imgUrl.put("url", "data:" + mimeType + ";base64," + base64Image);
            imgUrl.put("detail", "high");

            ObjectNode text = content.addObject();
            text.put("type", "text");
            text.put("text", buildAnalysisPrompt(productName, category));

            HttpResponse<String> response = post(visionUrl, body, 60);
            if (response.statusCode() != 200)
                throw new RuntimeException("GPT-4o Vision error " + response.statusCode() + ": " + response.body());

            return objectMapper.readTree(response.body())
                .path("choices").get(0).path("message").path("content").asText();

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Image analysis failed: " + e.getMessage(), e);
        }
    }

    public String analyzeProductImageByUrl(String imageUrl, String productName, String category) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", "gpt-4o");
            body.put("max_tokens", 400);

            ArrayNode messages = body.putArray("messages");
            ObjectNode message  = messages.addObject();
            message.put("role", "user");
            ArrayNode content = message.putArray("content");

            ObjectNode img = content.addObject();
            img.put("type", "image_url");
            img.putObject("image_url").put("url", imageUrl).put("detail", "low");

            ObjectNode text = content.addObject();
            text.put("type", "text");
            text.put("text", buildAnalysisPrompt(productName, category));

            HttpResponse<String> response = post(visionUrl, body, 30);
            if (response.statusCode() != 200)
                throw new RuntimeException("GPT-4o Vision error " + response.statusCode());

            return objectMapper.readTree(response.body())
                .path("choices").get(0).path("message").path("content").asText();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Image analysis by URL failed: " + e.getMessage(), e);
        }
    }

    public String generateDalleImage(String prompt) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", "dall-e-3");
            body.put("prompt", prompt);
            body.put("n", 1);
            body.put("size", "1024x1024");
            body.put("quality", "standard");

            HttpResponse<String> response = post(imageUrl, body, 120);
            if (response.statusCode() != 200)
                throw new RuntimeException("DALL-E error " + response.statusCode() + ": " + response.body());

            return objectMapper.readTree(response.body())
                .path("data").get(0).path("url").asText();

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Image generation failed: " + e.getMessage(), e);
        }
    }

    private HttpResponse<String> post(String url, ObjectNode body, int timeoutSeconds) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String buildAnalysisPrompt(String productName, String category) {
        return String.format(
            "Analyze this %s product image for an Indian fashion catalog. Product name: %s. " +
            "Describe concisely (under 150 words): fabric type and texture, primary and secondary colors, " +
            "pattern or design (paisley, floral, geometric, plain, etc.), embellishments (embroidery, " +
            "zari, sequins, prints, etc.), and distinctive style features. " +
            "This description will be used to generate professional catalog photos via DALL-E.",
            category != null && !category.isBlank() ? category : "fashion",
            productName != null && !productName.isBlank() ? productName : "product"
        );
    }
}

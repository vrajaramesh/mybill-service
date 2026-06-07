package com.example.mybill.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Service
public class FashionCLIPService {

    @Value("${fashionclip.service.url:http://localhost:8000}")
    private String baseUrl;

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
    private final ObjectMapper json = new ObjectMapper();

    public float[] embedText(String text) {
        ObjectNode body = json.createObjectNode();
        body.put("text", text);
        return call("/embed/text", body);
    }

    public float[] embedImageUrl(String imageUrl) {
        ObjectNode body = json.createObjectNode();
        body.put("url", imageUrl);
        return call("/embed/image-url", body);
    }

    public float[] embedImageBase64(String base64Data, String mimeType) {
        ObjectNode body = json.createObjectNode();
        body.put("base64_data", base64Data);
        body.put("mime_type", mimeType != null ? mimeType : "image/jpeg");
        return call("/embed/image-base64", body);
    }

    /**
     * Zero-shot garment classification using the shared CLIP embedding space.
     * Returns one of: Saree, Kurti, Salwar, Blouse, Frock, Lehenga, Dupatta, Fabric, Accessories.
     * Falls back to "Fabric" if the service is unreachable or the call fails.
     */
    public String classifyGarmentType(String imageUrl) {
        ObjectNode body = json.createObjectNode();
        body.put("url", imageUrl);
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/classify/garment"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .timeout(Duration.ofSeconds(30))
                .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200)
                throw new RuntimeException("FashionCLIP classify error " + resp.statusCode() + ": " + resp.body());
            return json.readTree(resp.body()).path("garmentType").asText("Fabric");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("FashionCLIP classify failed: " + e.getMessage(), e);
        }
    }

    public boolean isAvailable() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/health"))
                .GET().timeout(Duration.ofSeconds(3)).build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private float[] call(String path, ObjectNode body) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .timeout(Duration.ofSeconds(30))
                .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200)
                throw new RuntimeException("FashionCLIP service error " + resp.statusCode() + ": " + resp.body());
            JsonNode arr = json.readTree(resp.body()).path("embedding");
            float[] embedding = new float[arr.size()];
            for (int i = 0; i < arr.size(); i++) embedding[i] = arr.get(i).floatValue();
            return embedding;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("FashionCLIP call failed: " + e.getMessage(), e);
        }
    }
}

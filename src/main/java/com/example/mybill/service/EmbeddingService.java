package com.example.mybill.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Service
public class EmbeddingService {

    @Value("${openai.api.key}")
    private String openAiKey;

    @Value("${openai.embed.url}")
    private String openAiEmbedUrl;

    @Autowired
    private FashionCLIPService fashionCLIP;

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20)).build();
    private final ObjectMapper json = new ObjectMapper();

    private static final String OPENAI_MODEL = "text-embedding-3-small";

    /**
     * Embed a text query. Uses FashionCLIP if the service is running (512-dim),
     * falls back to OpenAI text-embedding-3-small (1536-dim) otherwise.
     */
    public float[] embed(String text) {
        if (fashionCLIP.isAvailable()) {
            return fashionCLIP.embedText(text);
        }
        return openAiEmbed(text);
    }

    public float[] embedImageBase64(String base64Data, String mimeType) {
        if (fashionCLIP.isAvailable()) {
            return fashionCLIP.embedImageBase64(base64Data, mimeType);
        }
        throw new RuntimeException("FashionCLIP service is not running — cannot embed uploaded image");
    }

    /**
     * Embed a product image URL. Uses FashionCLIP image encoder (visual understanding).
     * Falls back to embedding the text description via OpenAI if FashionCLIP is unavailable.
     */
    public float[] embedImageUrl(String imageUrl, String fallbackText) {
        if (fashionCLIP.isAvailable()) {
            try {
                return fashionCLIP.embedImageUrl(imageUrl);
            } catch (Exception e) {
                System.err.println("[EMBED] FashionCLIP image embed failed, using text fallback: " + e.getMessage());
            }
        }
        return openAiEmbed(fallbackText);
    }

    public String toVectorString(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(embedding[i]);
        }
        return sb.append(']').toString();
    }

    private float[] openAiEmbed(String text) {
        try {
            ObjectNode body = json.createObjectNode();
            body.put("model", OPENAI_MODEL);
            body.put("input", text);

            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(openAiEmbedUrl))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + openAiKey)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .timeout(Duration.ofSeconds(30))
                .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200)
                throw new RuntimeException("OpenAI Embedding API error " + resp.statusCode() + ": " + resp.body());

            JsonNode arr = json.readTree(resp.body()).path("data").get(0).path("embedding");
            float[] embedding = new float[arr.size()];
            for (int i = 0; i < arr.size(); i++) embedding[i] = arr.get(i).floatValue();
            return embedding;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("OpenAI embedding failed: " + e.getMessage(), e);
        }
    }
}

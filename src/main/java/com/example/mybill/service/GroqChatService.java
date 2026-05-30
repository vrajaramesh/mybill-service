package com.example.mybill.service;

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
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Service
public class GroqChatService {

    @Value("${groq.api.key}")
    private String apiKey;

    @Value("${groq.model:llama-3.3-70b-versatile}")
    private String model;

    @Value("${groq.api.url}")
    private String groqUrl;

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    public String chat(String firmName,
                       String userMessage,
                       List<Map<String, String>> history,
                       List<ProductSearchResult> products,
                       String ecomBaseUrl) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", model);
            body.put("max_tokens", 1024);

            ArrayNode messages = body.putArray("messages");

            // System prompt
            messages.addObject()
                .put("role", "system")
                .put("content", buildSystemPrompt(firmName));

            // Conversation history
            for (Map<String, String> msg : history) {
                messages.addObject()
                    .put("role", msg.get("role"))
                    .put("content", msg.get("content"));
            }

            // Current user turn with injected product context
            messages.addObject()
                .put("role", "user")
                .put("content", buildUserContent(userMessage, products, ecomBaseUrl));

            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(groqUrl))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .timeout(Duration.ofSeconds(40))
                .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200)
                throw new RuntimeException("Groq API " + resp.statusCode() + ": " + resp.body());

            return objectMapper.readTree(resp.body())
                .path("choices").get(0)
                .path("message").path("content").asText("").trim();

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Groq chat failed: " + e.getMessage(), e);
        }
    }

    private String buildSystemPrompt(String firmName) {
        return """
            You are a warm, knowledgeable fabric shopping assistant for %s.
            You specialize in Indian textiles — silks, cottons, georgettes, chiffons,
            linens, and more. You understand occasions (weddings, festivals, casual, formal),
            garments (sarees, lehengas, kurtas, blouses, suits), and can recommend fabrics
            by texture, drape, and purpose.

            IMPORTANT — how products are shown:
            - The chat UI automatically shows product photo cards below your message.
            - Customers can tap any card to see the product in the shop.
            - Do NOT include URLs or links in your text reply — the cards handle navigation.
            - NEVER say you cannot show products.

            Guidelines:
            - Keep responses to 2–3 short sentences maximum. Be concise.
            - Refer to products by their plain fabric name only (e.g. "Kalamkari Silk",
              "Tussar Silk"). NEVER mention SKU codes, product IDs, or any alphanumeric
              codes like KS003, KTS004, KC001 etc. Customers don't care about codes.
            - When products are provided, briefly say what they are and why they suit
              the need. Don't list every detail — just the most relevant 1–2 facts.
            - If no matching products exist, say so in one sentence and suggest what to look for.
            - Ask at most one short follow-up question.
            - Prices are in Indian Rupees (₹) per meter unless otherwise noted.
            - Never invent product details not provided to you.
            """.formatted(firmName);
    }

    // ── Product description generation (vision) ───────────────────────────

    private static final String VISION_MODEL = "meta-llama/llama-4-scout-17b-16e-instruct";

    public String generateFabricDescription(String productName, String category,
                                             String suitableFor, String tags,
                                             String imageUrl) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", VISION_MODEL);
            body.put("max_tokens", 150);

            ArrayNode messages = body.putArray("messages");
            ObjectNode userMsg = messages.addObject();
            userMsg.put("role", "user");
            ArrayNode content = userMsg.putArray("content");

            // Attach image as base64 data URL if available
            if (imageUrl != null && !imageUrl.isBlank()) {
                try {
                    HttpRequest imgReq = HttpRequest.newBuilder()
                        .uri(URI.create(imageUrl))
                        .GET().timeout(Duration.ofSeconds(20)).build();
                    HttpResponse<byte[]> imgResp = httpClient.send(imgReq, HttpResponse.BodyHandlers.ofByteArray());
                    if (imgResp.statusCode() == 200 && imgResp.body().length > 0) {
                        String base64 = Base64.getEncoder().encodeToString(imgResp.body());
                        String mime = detectImageMime(imageUrl);
                        content.addObject()
                            .put("type", "image_url")
                            .putObject("image_url")
                            .put("url", "data:" + mime + ";base64," + base64);
                    }
                } catch (Exception e) {
                    System.err.println("[DESC] Image download skipped: " + e.getMessage());
                }
            }

            content.addObject().put("type", "text").put("text",
                buildDescriptionPrompt(productName, category, suitableFor, tags));

            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(groqUrl))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .timeout(Duration.ofSeconds(40))
                .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200)
                throw new RuntimeException("Groq Vision API " + resp.statusCode() + ": " + resp.body());

            return objectMapper.readTree(resp.body())
                .path("choices").get(0)
                .path("message").path("content").asText("").trim();

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Groq description generation failed: " + e.getMessage(), e);
        }
    }

    private String buildDescriptionPrompt(String productName, String category,
                                           String suitableFor, String tags) {
        StringBuilder sb = new StringBuilder();
        sb.append("Look at the fabric image and write ONE sentence description for a product catalog.\n\n");
        sb.append("Format: [COLOR] [FABRIC TYPE] with [PATTERN/DESIGN], ideal for [USE CASES].\n\n");
        sb.append("Rules:\n");
        sb.append("- Describe the actual color you see (e.g. deep red, mustard yellow, navy blue, off-white)\n");
        sb.append("- Fabric type: use the product name/category (e.g. cotton, silk, georgette)\n");
        sb.append("- Pattern: describe what you actually see (e.g. small white floral print, gold zari border, indigo stripes)\n");
        sb.append("- Use cases: list garments from 'Suitable For' below\n");
        sb.append("- Output ONLY the one sentence — no extra text, no explanation\n");
        sb.append("- NEVER use: timeless, elegant, premium, luxurious, effortless, sophisticated, versatile\n\n");
        sb.append("Product info:\n");
        sb.append("Name: ").append(productName != null ? productName : "").append("\n");
        if (category != null && !category.isBlank())
            sb.append("Category: ").append(category).append("\n");
        if (suitableFor != null && !suitableFor.isBlank())
            sb.append("Suitable For: ").append(suitableFor).append("\n");
        if (tags != null && !tags.isBlank())
            sb.append("Occasions: ").append(tags).append("\n");
        sb.append("\nExample: Deep red silk with dense gold zari floral weave, ideal for sarees and lehengas.");
        return sb.toString();
    }

    private String detectImageMime(String url) {
        String lower = url.toLowerCase();
        if (lower.contains(".png"))  return "image/png";
        if (lower.contains(".webp")) return "image/webp";
        if (lower.contains(".gif"))  return "image/gif";
        return "image/jpeg";
    }

    private String buildUserContent(String userMessage, List<ProductSearchResult> products,
                                    String ecomBaseUrl) {
        if (products == null || products.isEmpty()) return userMessage;
        StringBuilder sb = new StringBuilder(userMessage);
        sb.append("\n\n[Matching products from our catalog:]\n");
        for (int i = 0; i < products.size(); i++) {
            ProductSearchResult p = products.get(i);
            String cleanName = p.getProductName()
                .replaceAll("\\s*\\(SKU[:\\s][^)]*\\)", "")
                .replaceAll("\\s*\\([A-Z]{1,5}\\d{3,6}\\)", "")
                .trim();
            sb.append(i + 1).append(". ").append(cleanName)
              .append(" — ₹").append(p.getSellingPrice()).append("/meter");
            if (p.getCategoryName() != null)
                sb.append(" | Category: ").append(p.getCategoryName());
            if (p.getTags() != null && !p.getTags().isBlank())
                sb.append(" | Occasions: ").append(p.getTags());
            sb.append("\n");
        }
        return sb.toString();
    }
}
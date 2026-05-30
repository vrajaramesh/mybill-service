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
public class GeminiService {

    @Value("${gemini.api.key}")
    private String apiKey;

    @Value("${gemini.api.url}")
    private String geminiUrl;

    @Value("${gemini.chat.url}")
    private String geminiChatUrl;

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    public String generateFabricDescription(String productName, String category,
                                             String suitableFor, String tags,
                                             String imageUrl) {
        try {
            String prompt = buildDescriptionPrompt(productName, category, suitableFor, tags);

            ObjectNode body = objectMapper.createObjectNode();
            ArrayNode contents = body.putArray("contents");
            ArrayNode parts = contents.addObject().putArray("parts");

            if (imageUrl != null && !imageUrl.isBlank()) {
                try {
                    HttpRequest imgReq = HttpRequest.newBuilder()
                        .uri(URI.create(imageUrl))
                        .GET().timeout(Duration.ofSeconds(20)).build();
                    HttpResponse<byte[]> imgResp = httpClient.send(imgReq, HttpResponse.BodyHandlers.ofByteArray());
                    if (imgResp.statusCode() == 200 && imgResp.body().length > 0) {
                        String base64 = Base64.getEncoder().encodeToString(imgResp.body());
                        String mime = detectMime(imageUrl);
                        ObjectNode inlineData = parts.addObject().putObject("inline_data");
                        inlineData.put("mime_type", mime);
                        inlineData.put("data", base64);
                    }
                } catch (Exception imgEx) {
                    System.err.println("[DESC] Image download skipped: " + imgEx.getMessage());
                }
            }

            parts.addObject().put("text", prompt);

            body.putObject("generationConfig").put("maxOutputTokens", 150);

            String url = geminiChatUrl + "?key=" + apiKey;
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .timeout(Duration.ofSeconds(40))
                .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200)
                throw new RuntimeException("Gemini API " + resp.statusCode() + ": " + resp.body());

            return objectMapper.readTree(resp.body())
                .path("candidates").get(0)
                .path("content").path("parts").get(0)
                .path("text").asText("").trim();

        } catch (RuntimeException e) {
            System.err.println("[DESC] Gemini call failed: " + e.getMessage());
            throw e;
        } catch (Exception e) {
            System.err.println("[DESC] Gemini call failed: " + e.getMessage());
            throw new RuntimeException("Gemini description generation failed: " + e.getMessage(), e);
        }
    }

    private String buildDescriptionPrompt(String productName, String category,
                                           String suitableFor, String tags) {
        StringBuilder sb = new StringBuilder();
        sb.append("Look at the fabric image carefully and fill in this exact template:\n\n");
        sb.append("[ACTUAL COLOR from photo] [FABRIC TYPE] with [SPECIFIC PATTERN/DESIGN from photo], ");
        sb.append("ideal for [USE CASE].\n\n");
        sb.append("Rules — strictly follow:\n");
        sb.append("- Replace every bracketed placeholder with what you actually observe in the image\n");
        sb.append("- COLOR: name the real color you see (e.g. deep red, mustard yellow, navy blue, off-white)\n");
        sb.append("- FABRIC TYPE: use the product name/category (e.g. cotton, silk, georgette, linen)\n");
        sb.append("- PATTERN: describe the actual print/weave you see (e.g. small white floral print, gold zari border, indigo stripes, abstract batik)\n");
        sb.append("- USE CASE: list ALL suitable garments from the 'Suitable For' field, joined with commas\n");
        sb.append("- Output ONLY the completed sentence, nothing else\n");
        sb.append("- FORBIDDEN words: timeless, elegant, bloom, premium, luxurious, effortless, sophisticated, versatile\n\n");
        sb.append("Product info:\n");
        sb.append("Name: ").append(productName != null ? productName : "").append("\n");
        if (category != null && !category.isBlank())
            sb.append("Category: ").append(category).append("\n");
        if (suitableFor != null && !suitableFor.isBlank())
            sb.append("Suitable For: ").append(suitableFor).append("\n");
        if (tags != null && !tags.isBlank())
            sb.append("Occasions: ").append(tags).append("\n");
        sb.append("\nGood example: Deep red silk with dense gold zari floral weave, ideal for sarees and lehengas.\n");
        sb.append("Good example: Mustard yellow cotton with small white block-printed dots, perfect for kurtis, dresses, and kids wear.");
        return sb.toString();
    }

    public String chat(String firmName,
                      String userMessage,
                      List<Map<String, String>> history,
                      List<ProductSearchResult> products,
                      String ecomBaseUrl) {
        try {
            ObjectNode body = objectMapper.createObjectNode();

            // System instruction
            ObjectNode sysInstr = body.putObject("systemInstruction");
            sysInstr.putArray("parts").addObject().put("text", buildChatSystemPrompt(firmName));

            // Conversation history + current turn
            ArrayNode contents = body.putArray("contents");
            for (Map<String, String> msg : history) {
                String geminiRole = "assistant".equals(msg.get("role")) ? "model" : "user";
                ObjectNode turn = contents.addObject();
                turn.put("role", geminiRole);
                turn.putArray("parts").addObject().put("text", msg.get("content"));
            }

            // Current user turn with product context injected
            ObjectNode userTurn = contents.addObject();
            userTurn.put("role", "user");
            userTurn.putArray("parts").addObject().put("text", buildUserContent(userMessage, products, ecomBaseUrl));

            ObjectNode chatConfig = body.putObject("generationConfig");
            chatConfig.put("maxOutputTokens", 1024);
            chatConfig.putObject("thinkingConfig").put("thinkingBudget", 0);

            String url = geminiChatUrl + "?key=" + apiKey;
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .timeout(Duration.ofSeconds(40))
                .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200)
                throw new RuntimeException("Gemini chat API " + resp.statusCode() + ": " + resp.body());

            ArrayNode chatParts = (ArrayNode) objectMapper.readTree(resp.body())
                .path("candidates").get(0).path("content").path("parts");
            for (int i = chatParts.size() - 1; i >= 0; i--) {
                if (!chatParts.get(i).path("thought").asBoolean(false))
                    return chatParts.get(i).path("text").asText("").trim();
            }
            return "";

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Gemini chat failed: " + e.getMessage(), e);
        }
    }

    private String buildChatSystemPrompt(String firmName) {
        return """
            You are a warm, knowledgeable fabric shopping assistant for %s.
            You specialize in Indian textiles — silks, cottons, georgettes, chiffons,
            linens, and more. You understand occasions (weddings, festivals, casual, formal),
            garments (sarees, lehengas, kurtas, blouses, suits), and can recommend fabrics
            by texture, drape, and purpose.

            IMPORTANT — how product photos work:
            - Each product has a "Photo:" URL (product image) and a "View:" URL (product page).
            - When a customer asks for pics or a link, share the View URL so they can open
              the product directly on the shop. Share as a plain URL on its own line.
            - Example: "Here is the Kalamkari Silk: http://localhost:4200/srisa?product=1004"
            - The chat UI also shows photo cards automatically below your message.
            - NEVER say you cannot send, show, or link photos or products.

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
            if (p.getFirstImageUrl() != null && !p.getFirstImageUrl().isBlank())
                sb.append(" | Photo: ").append(p.getFirstImageUrl());
            if (ecomBaseUrl != null && !ecomBaseUrl.isBlank())
                sb.append(" | View: ").append(ecomBaseUrl).append("?product=").append(p.getProductId());
            sb.append("\n");
        }
        return sb.toString();
    }

    public String analyzeProductImage(String base64Image, String mimeType,
                                      String productName, String category) {
        try {
            return callGemini(base64Image, mimeType, buildPrompt(productName, category));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Gemini image analysis failed: " + e.getMessage(), e);
        }
    }

    public String analyzeProductImageByUrl(String imageUrl, String productName, String category) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(imageUrl))
                .GET()
                .timeout(Duration.ofSeconds(30))
                .build();
            HttpResponse<byte[]> imgResp = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
            String base64 = Base64.getEncoder().encodeToString(imgResp.body());
            String mime = detectMime(imageUrl);
            return callGemini(base64, mime, buildPrompt(productName, category));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Gemini image analysis by URL failed: " + e.getMessage(), e);
        }
    }

    private String callGemini(String base64Image, String mimeType, String prompt) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        ArrayNode contents = body.putArray("contents");
        ObjectNode content = contents.addObject();
        ArrayNode parts = content.putArray("parts");

        ObjectNode inlineData = parts.addObject().putObject("inline_data");
        inlineData.put("mime_type", mimeType);
        inlineData.put("data", base64Image);

        parts.addObject().put("text", prompt);

        body.putObject("generationConfig").put("maxOutputTokens", 300);

        String url = geminiUrl + "?key=" + apiKey;
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
            .timeout(Duration.ofSeconds(60))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200)
            throw new RuntimeException("Gemini API error " + response.statusCode() + ": " + response.body());

        return objectMapper.readTree(response.body())
            .path("candidates").get(0)
            .path("content").path("parts").get(0)
            .path("text").asText();
    }

    private String buildPrompt(String productName, String category) {
        return String.format(
            "Analyze this %s product image for an Indian fashion catalog. Product name: %s. " +
            "Describe concisely (under 150 words): fabric type and texture, primary and secondary colors, " +
            "pattern or design (paisley, floral, geometric, plain, etc.), embellishments (embroidery, " +
            "zari, sequins, prints, etc.), and distinctive style features. " +
            "This description will be used to generate professional catalog photos.",
            category != null && !category.isBlank() ? category : "fashion",
            productName != null && !productName.isBlank() ? productName : "product"
        );
    }

    private String detectMime(String url) {
        String lower = url.toLowerCase();
        if (lower.contains(".png")) return "image/png";
        if (lower.contains(".webp")) return "image/webp";
        if (lower.contains(".gif")) return "image/gif";
        return "image/jpeg";
    }
}

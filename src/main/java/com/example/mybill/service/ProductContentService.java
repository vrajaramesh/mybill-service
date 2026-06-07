package com.example.mybill.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Generates Instagram captions, WhatsApp marketing text, hashtags, and SEO
 * descriptions for a product using Claude. Called by HermesAgentService.
 */
@Service
public class ProductContentService {

    @Autowired private ClaudeService claudeService;

    private final ObjectMapper json = new ObjectMapper();

    public ProductMarketingContent generate(Product product, String firmName) {
        String raw = claudeService.complete(systemPrompt(), userPrompt(product, firmName));
        return parseResponse(raw, product.getProductId());
    }

    // ── Prompts ──────────────────────────────────────────────────────────────

    private String systemPrompt() {
        return """
            You are a marketing copywriter for Indian fabric and textile boutiques.
            You write engaging, authentic social media content that feels personal and premium.
            You understand Indian fabrics, occasions, and regional fashion sensibilities.

            IMPORTANT: Respond with ONLY a valid JSON object. No markdown, no code fences.
            All text must be ready to copy-paste — no placeholders like [shop name].
            """;
    }

    private String userPrompt(Product product, String firmName) {
        StringBuilder sb = new StringBuilder();
        sb.append("Generate marketing content for this fabric product sold by ").append(firmName).append(".\n\n");
        sb.append("Product Name: ").append(product.getProductName()).append("\n");
        if (product.getCategory() != null && product.getCategory().getCategoryName() != null)
            sb.append("Category: ").append(product.getCategory().getCategoryName()).append("\n");
        if (product.getSuitableFor() != null && !product.getSuitableFor().isBlank())
            sb.append("Suitable For: ").append(product.getSuitableFor()).append("\n");
        if (product.getTags() != null && !product.getTags().isBlank())
            sb.append("Occasion/Style Tags: ").append(product.getTags()).append("\n");
        if (product.getSellingPrice() != null)
            sb.append("Price: ₹").append(product.getSellingPrice()).append(" per meter\n");
        if (product.getDescription() != null && !product.getDescription().isBlank())
            sb.append("Description: ").append(product.getDescription()).append("\n");

        sb.append("""

            Return ONLY this JSON:
            {
              "instagram_caption": "3-5 line Instagram caption with 1-2 relevant emojis. Evoke the feeling/occasion. End with a soft CTA like 'DM to order'.",
              "whatsapp_text": "2-3 line WhatsApp broadcast message. Warm, conversational tone. Include price. No emojis overload.",
              "hashtags": "12-15 hashtags as a single space-separated string. Mix fabric-specific, occasion, and brand hashtags. Start each with #.",
              "seo_description": "2-3 sentence product description optimised for search. Include fabric type, occasion, garment type naturally."
            }
            """);

        return sb.toString();
    }

    // ── Response parsing ─────────────────────────────────────────────────────

    private ProductMarketingContent parseResponse(String raw, Integer productId) {
        ProductMarketingContent content = new ProductMarketingContent();
        content.setProductId(productId);
        content.setGeneratedAt(LocalDateTime.now());
        content.setUpdatedAt(LocalDateTime.now());

        try {
            String cleaned = raw.trim();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("^```[a-z]*\\n?", "").replaceAll("```$", "").trim();
            }
            JsonNode node = json.readTree(cleaned);
            content.setInstagramCaption(node.path("instagram_caption").asText(""));
            content.setWhatsappText(node.path("whatsapp_text").asText(""));
            content.setHashtags(node.path("hashtags").asText(""));
            content.setSeoDescription(node.path("seo_description").asText(""));
        } catch (Exception e) {
            System.err.println("[Hermes] Content parse failed for product " + productId + ": " + e.getMessage());
            content.setWhatsappText(raw.length() > 500 ? raw.substring(0, 500) : raw);
        }

        return content;
    }
}

package com.example.mybill.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Month;
import java.time.MonthDay;
import java.time.LocalDate;
import java.util.logging.Logger;

@Service
public class HashtagGeneratorService {

    @Autowired private ClaudeService claudeService;

    private static final Logger log = Logger.getLogger(HashtagGeneratorService.class.getName());

    private static final String SYSTEM_PROMPT =
        "You are an expert Instagram growth strategist specialising in Indian ethnic fashion, " +
        "sarees, handloom fabrics, and boutique clothing. " +
        "You know which hashtags are currently trending on Instagram Reels for Indian fashion content. " +
        "Your job: generate exactly 28 hashtags for a given fabric product. " +
        "Rules: " +
        "1. Mix high-traffic tags (millions of posts) with niche product-specific tags. " +
        "2. Include tags for the specific fabric type, weave, color, and occasion. " +
        "3. Include 3-4 regional / city tags relevant to Indian buyers. " +
        "4. Include 4-5 currently trending Reels discovery tags (#reelsviral #exploremore etc.). " +
        "5. Always include #srisafabrics. " +
        "6. Output ONLY the hashtags on a single line, space-separated, each starting with #. " +
        "7. No explanation, no numbering, no line breaks between tags. " +
        "8. All hashtags must be lowercase, no spaces within a hashtag.";

    // Static fallback if Claude is unavailable
    private static final String FALLBACK_TAGS =
        "#reelsindia #reelsinstagram #trending #viral #explore #explorepage " +
        "#saree #sareelove #sareecollection #sareestyle #sareeaddict #sareeswag " +
        "#handloomsaree #handloom #indianhandloom #weavingart #fabriclove " +
        "#indianwear #ethnicwear #indianfashion #ethniclook " +
        "#bridalsaree #weddingwear #festivewear #partywear " +
        "#srisafabrics #hyderabadfashion #newarrival #sareenotsorry";

    /**
     * Generates trending, product-specific Instagram hashtags using Claude.
     * Falls back to a static set if the API call fails.
     */
    public String generateHashtags(Product product) {
        return generateHashtags(product, null);
    }

    public String generateHashtags(Product product, String customTitle) {
        try {
            String userPrompt = buildPrompt(product, customTitle);
            log.info("[Hashtags] Generating tags for: " + (product != null ? product.getProductName() : "unknown")
                + (customTitle != null && !customTitle.isBlank() ? " | title: " + customTitle : ""));
            String result = claudeService.complete(SYSTEM_PROMPT, userPrompt);
            String tags = extractHashtags(result);
            log.info("[Hashtags] Generated: " + tags);
            return tags;
        } catch (Exception e) {
            log.warning("[Hashtags] Claude call failed, using fallback: " + e.getMessage());
            return FALLBACK_TAGS;
        }
    }

    private String buildPrompt(Product product, String customTitle) {
        StringBuilder sb = new StringBuilder();
        sb.append("Generate trending Instagram hashtags for this fabric product:\n\n");

        if (customTitle != null && !customTitle.isBlank())
            sb.append("Reel caption/title (user-provided): ").append(customTitle).append("\n");

        if (product != null) {
            sb.append("Product name: ").append(product.getProductName()).append("\n");
            if (product.getCategory() != null)
                sb.append("Category: ").append(product.getCategory().getCategoryName()).append("\n");
            if (product.getSuitableFor() != null && !product.getSuitableFor().isBlank())
                sb.append("Suitable for: ").append(product.getSuitableFor()).append("\n");
            if (product.getTags() != null && !product.getTags().isBlank())
                sb.append("Occasion/style tags: ").append(product.getTags()).append("\n");
            if (product.getSellingPrice() != null)
                sb.append("Price: ₹").append(product.getSellingPrice()).append("\n");
        }

        sb.append("Current month: ").append(LocalDate.now().getMonth().getDisplayName(
            java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)).append("\n");
        sb.append("\nGenerate 28 hashtags that will maximise reach and virality for this specific product on Instagram Reels.");

        return sb.toString();
    }

    private String extractHashtags(String raw) {
        if (raw == null || raw.isBlank()) return FALLBACK_TAGS;
        // Keep only tokens that start with #
        String[] tokens = raw.trim().split("\\s+");
        StringBuilder result = new StringBuilder();
        for (String token : tokens) {
            String clean = token.replaceAll("[^#a-zA-Z0-9]", "");
            if (clean.startsWith("#") && clean.length() > 1) {
                if (!result.isEmpty()) result.append(" ");
                result.append(clean.toLowerCase());
            }
        }
        return result.isEmpty() ? FALLBACK_TAGS : result.toString();
    }
}

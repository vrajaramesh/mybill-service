package com.example.mybill.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class ProductPhotoPromptBuilder {

    public List<String> buildPrompts(String productDescription, String category,
                                     String productName, String suitableFor) {
        String cat  = category != null ? category.toLowerCase() : "";
        String name = productName != null ? productName : "product";
        String desc = productDescription != null ? productDescription : "";

        // If suitableFor is set, generate usage-driven prompts (fabric use case)
        if (suitableFor != null && !suitableFor.isBlank()) {
            return buildFromUsage(desc, name, suitableFor);
        }

        // Fall back to category-based prompts
        if (any(cat, "saree", "sari", "silk saree", "pattu", "kanjeevaram", "banarasi", "georgette saree")) {
            return saree(desc, name);
        } else if (any(cat, "lehenga", "ghagra", "bridal")) {
            return lehenga(desc, name);
        } else if (any(cat, "kurti", "kurta", "top", "blouse", "shirt", "tunic")) {
            return kurti(desc, name);
        } else if (any(cat, "salwar", "churidar", "palazzo", "suit")) {
            return kurti(desc, name);
        } else if (any(cat, "fabric", "cloth", "material", "dupatta", "stole", "silk", "cotton", "chiffon", "linen")) {
            return fabric(desc, name);
        } else if (any(cat, "jewel", "necklace", "earring", "bangle", "bracelet")) {
            return jewellery(desc, name);
        } else {
            return defaultStyle(desc, name, category);
        }
    }

    // Legacy overload without suitableFor — delegates to new method
    public List<String> buildPrompts(String productDescription, String category, String productName) {
        return buildPrompts(productDescription, category, productName, null);
    }

    // ── Usage-driven prompts ──────────────────────────────────────────────────

    private List<String> buildFromUsage(String desc, String name, String suitableFor) {
        List<String> usages = Arrays.stream(suitableFor.split(","))
            .map(String::trim).filter(s -> !s.isEmpty())
            .collect(Collectors.toList());

        String base = preamble("fabric", name, desc);
        List<String> prompts = new ArrayList<>();

        // Up to 2 garment-wear shots based on declared usage
        for (String usage : usages) {
            if (prompts.size() >= 2) break;
            String shot = garmentShot(base, usage);
            if (shot != null) prompts.add(shot);
        }

        // Always add flat-lay and texture close-up
        prompts.add(base + "Fabric bolt neatly rolled and displayed on a polished wooden stand with a small " +
                    "folded swatch, retail display photography, clean white background");
        prompts.add(base + "Extreme close-up macro of the fabric weave, thread count, print or embroidery detail, " +
                    "colors vivid and sharp, shallow depth of field, professional textile product photography");

        return prompts;
    }

    private String garmentShot(String base, String usage) {
        return switch (usage.toLowerCase()) {
            case "saree" -> base + "Elegant Indian woman model (age 25-30) wearing the fabric draped as a saree " +
                "in classic Nivi style, heritage haveli courtyard, natural daylight, full-body fashion shot";
            case "kurti" -> base + "Young Indian woman model wearing the fabric stitched as a kurti with " +
                "matching bottom wear, casual daytime café setting, natural warm sunlight, lifestyle fashion photo";
            case "dress" -> base + "Indian woman model wearing the fabric as an elegant midi dress, " +
                "clean studio with gradient white background, professional fashion photography lighting, full-body shot";
            case "frock" -> base + "5-year-old cute Indian girl wearing the fabric as a flared frock, " +
                "bright outdoor garden setting, cheerful expression, playful natural pose, soft natural light, kids fashion photography";
            case "blouse" -> base + "Indian woman model wearing the fabric as a saree blouse paired with a " +
                "contrasting saree, close-up portrait showing the blouse detailing, soft studio lighting";
            case "lehenga" -> base + "Indian bride or model wearing the fabric as a lehenga skirt with blouse " +
                "and dupatta, decorated wedding garden venue, fairy lights, bridal fashion photography, full-body shot";
            case "salwar" -> base + "Indian woman model wearing the fabric as a salwar kameez with dupatta, " +
                "clean professional studio background, full-body fashion photography";
            case "dupatta" -> base + "Indian woman model with the fabric gracefully draped as a dupatta over " +
                "a traditional outfit, flowing movement, soft natural window light, portrait fashion shot";
            case "kids wear" -> base + "Happy child model (age 6-10) wearing the fabric as a colorful kids outfit, " +
                "bright playful outdoor setting, cheerful natural light, kids fashion photography";
            default -> null;
        };
    }

    // ── Category-based prompts ────────────────────────────────────────────────

    private List<String> saree(String desc, String name) {
        String base = preamble("saree", name, desc);
        return List.of(
            base + "Neatly folded saree with pleats artistically fanned out, placed on polished white marble " +
                   "surface with scattered rose petals and marigold garlands, soft diffused studio lighting, " +
                   "high-end fashion catalog photography",
            base + "Elegant Indian woman model (age 25-30) wearing the saree draped in classic Nivi style, " +
                   "standing at a sunlit heritage haveli courtyard with carved archways, natural daylight, " +
                   "graceful pose, full-body shot, Vogue India editorial style",
            base + "Elegant Indian woman model wearing the saree with full traditional gold jewellery, " +
                   "indoor setting with grand chandeliers and fairy lights, golden evening ambiance, " +
                   "cocktail party or wedding reception, full-body fashion shot, luxury editorial",
            base + "Extreme close-up macro shot of the saree border design, weave pattern, and fabric texture, " +
                   "embroidery or zari detail in sharp focus, shallow depth of field, professional product photography"
        );
    }

    private List<String> lehenga(String desc, String name) {
        String base = preamble("lehenga", name, desc);
        return List.of(
            base + "Luxury display of the complete lehenga set (skirt, blouse, dupatta) artistically arranged " +
                   "on rich velvet fabric with traditional Indian decor, candles and marigold garlands, " +
                   "high-end bridal fashion catalog photography",
            base + "Elegant Indian bride or model wearing the lehenga, standing at a decorated outdoor garden " +
                   "venue with floral backdrop and fairy lights, natural daylight, bridal fashion photography, full-body shot",
            base + "Indian woman model wearing the lehenga at a grand wedding hall with ornate pillars and " +
                   "crystal chandeliers, festive golden lighting, full-body shot, luxury bridal editorial",
            base + "Extreme close-up macro of the lehenga embroidery, zari work, or stone embellishment " +
                   "on the skirt border or blouse, extreme texture detail, professional product photography"
        );
    }

    private List<String> kurti(String desc, String name) {
        String base = preamble("kurti", name, desc);
        return List.of(
            base + "Flat lay product photo of the kurti neatly spread on a clean white linen surface, " +
                   "styled with minimal accessories (bangles, small earrings), top-down overhead shot, " +
                   "soft even natural lighting, fashion catalog",
            base + "Young Indian woman model (age 22-28) wearing the kurti paired with matching bottom wear, " +
                   "casual daytime setting at a bright café or garden patio, natural warm sunlight, " +
                   "lifestyle fashion photography",
            base + "Indian woman model wearing the kurti in a clean professional studio, gradient white " +
                   "background, full-body and 3/4 portrait shot, professional fashion photography lighting",
            base + "Close-up detail of the kurti neckline embroidery, print pattern, or fabric texture, " +
                   "macro photography, sharp and vibrant, clean background, product detail shot"
        );
    }

    private List<String> fabric(String desc, String name) {
        String base = preamble("fabric", name, desc);
        return List.of(
            base + "Fabric bolt neatly rolled and displayed on a polished wooden stand with a small folded " +
                   "swatch showing the face side, retail display photography, clean white background",
            base + "Fabric gracefully draped and flowing over a smooth marble surface, showing the natural " +
                   "fall and movement of the material, soft lighting, artistic textile photography",
            base + "Extreme close-up macro of the fabric weave, thread count, print or embroidery detail, " +
                   "colors vivid and sharp, shallow depth of field, professional textile product photography",
            base + "Multiple neatly folded fabric swatches arranged in an overlapping fan pattern on clean " +
                   "marble, showing color and texture, minimal aesthetic, color palette showcase"
        );
    }

    private List<String> jewellery(String desc, String name) {
        String base = preamble("jewellery", name, desc);
        return List.of(
            base + "Jewellery piece displayed on clean white velvet jewelry stand, professional studio " +
                   "lighting with reflections, high-end product photography",
            base + "Jewellery styled on an elegant Indian woman model, close-up portrait showing the piece " +
                   "worn naturally, soft backlit lighting, fashion jewelry photography",
            base + "Flat lay of the jewellery piece on black velvet with subtle sparkle lighting, " +
                   "luxury jewelry catalog photography",
            base + "Macro close-up of the jewellery showing intricate craftsmanship, stone clarity, " +
                   "and metal texture, extreme detail product photography"
        );
    }

    private List<String> defaultStyle(String desc, String name, String category) {
        String cat  = category != null ? " (" + category + ")" : "";
        String base = preamble("fashion product" + cat, name, desc);
        return List.of(
            base + "Clean white background professional product catalog photo, studio lighting, " +
                   "shadows removed, e-commerce style",
            base + "Indian woman model wearing or holding the product, day wear lifestyle setting, " +
                   "natural outdoor background, fashion photography",
            base + "Styled product shot with complementary fashion accessories, aesthetic background " +
                   "with plants or minimal Indian decor, fashion catalog",
            base + "Close-up detail shot showing texture, pattern, or unique design features of the " +
                   "product, macro photography on clean background"
        );
    }

    private String preamble(String type, String name, String desc) {
        return String.format(
            "Ultra-realistic 8K professional Indian fashion catalog photo. " +
            "Product type: %s. Product name: %s. Visual details: %s. Style: ", type, name, desc
        );
    }

    private boolean any(String text, String... keywords) {
        for (String kw : keywords) if (text.contains(kw)) return true;
        return false;
    }
}

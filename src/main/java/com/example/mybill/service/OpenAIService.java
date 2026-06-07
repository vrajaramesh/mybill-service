package com.example.mybill.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Service
public class OpenAIService {

    @Value("${openai.api.key}")
    private String apiKey;

    @Value("${openai.vision.url}")
    private String visionUrl;

    @Value("${openai.image.url}")
    private String imageUrl;

    @Value("${openai.image.edit.url}")
    private String imageEditUrl;

    @Value("${openai.responses.url}")
    private String responsesUrl;

    @Lazy @Autowired
    private ImageUploadService imageUploadService;

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

    // gpt-image-1: returns base64 PNG.
    // generateDalleImage = medium quality, generateHdPhoto = high quality.

    public String generateDalleImage(String prompt) {
        return generateImage(prompt, "medium");
    }

    public String generateHdPhoto(String prompt) {
        return generateImage(prompt, "high");
    }

    // Bytes-based generation: sends image bytes + prompt to /v1/images/edits (gpt-image-1).
    // No compression — original bytes are sent as-is to preserve fabric detail.
    public String generateImageWithBytes(byte[] imageBytes, String contentType, String prompt, String quality) {
        try {
            String mime = contentType != null ? contentType : "image/jpeg";
            String ext  = mime.contains("png") ? "png" : "jpg";

            logImageDimensions("[OPENAI-EDIT] Input image", imageBytes, mime);

            String boundary = "----OpenAIBoundary" + Long.toHexString(System.currentTimeMillis());
            byte[] multipart = buildEditMultipart(boundary, imageBytes, mime, ext, prompt, quality);

            System.err.println("[OPENAI-EDIT] ══ REQUEST ════════════════════════════════════════");
            System.err.println("[OPENAI-EDIT]   endpoint      : " + imageEditUrl);
            System.err.println("[OPENAI-EDIT]   model         : gpt-image-1");
            System.err.println("[OPENAI-EDIT]   quality       : " + quality);
            System.err.println("[OPENAI-EDIT]   size          : 1024x1024");
            System.err.println("[OPENAI-EDIT]   n             : 1");
            System.err.println("[OPENAI-EDIT]   image type    : " + mime);
            System.err.println("[OPENAI-EDIT]   image size    : " + kb(imageBytes.length) + " KB");
            System.err.println("[OPENAI-EDIT]   multipart     : " + kb(multipart.length) + " KB");
            System.err.println("[OPENAI-EDIT]   prompt        : " + prompt);
            System.err.println("[OPENAI-EDIT] ══════════════════════════════════════════════════");

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(imageEditUrl))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipart))
                .timeout(Duration.ofSeconds(240))
                .build();

            long t0 = System.currentTimeMillis();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long elapsed = System.currentTimeMillis() - t0;

            System.err.println("[OPENAI-EDIT] ══ RESPONSE ══════════════════════════════════════");
            System.err.println("[OPENAI-EDIT]   http status   : " + response.statusCode());
            System.err.println("[OPENAI-EDIT]   elapsed ms    : " + elapsed);
            System.err.println("[OPENAI-EDIT]   content-type  : "
                + response.headers().firstValue("content-type").orElse("(none)"));

            String bodyStr = response.body();
            System.err.println("[OPENAI-EDIT]   body length   : " + bodyStr.length() + " chars");
            System.err.println("[OPENAI-EDIT]   body (2000)   : "
                + bodyStr.substring(0, Math.min(2000, bodyStr.length())));

            if (response.statusCode() != 200)
                throw new RuntimeException("Image edit error " + response.statusCode() + ": " + bodyStr);

            JsonNode respJson = objectMapper.readTree(bodyStr);

            // Log model, usage, token counts
            System.err.println("[OPENAI-EDIT]   resp model    : " + respJson.path("model").asText("(not set)"));
            JsonNode usage = respJson.path("usage");
            if (!usage.isMissingNode())
                System.err.println("[OPENAI-EDIT]   usage         : " + usage);

            JsonNode dataArr = respJson.path("data");
            System.err.println("[OPENAI-EDIT]   data[] count  : " + (dataArr.isArray() ? dataArr.size() : "N/A"));

            if (!dataArr.isArray() || dataArr.isEmpty())
                throw new RuntimeException("OpenAI returned empty data[]. Full body: " + bodyStr);

            JsonNode first = dataArr.get(0);
            String revisedPrompt = first.path("revised_prompt").asText("");
            if (!revisedPrompt.isBlank())
                System.err.println("[OPENAI-EDIT]   revised_prompt: " + revisedPrompt);

            String b64 = first.path("b64_json").asText();
            long decodedKb = b64.length() * 3L / 4 / 1024;
            System.err.println("[OPENAI-EDIT]   b64_json len  : " + b64.length() + " chars (~" + decodedKb + " KB)");
            System.err.println("[OPENAI-EDIT] ══════════════════════════════════════════════════");

            System.err.println("[OPENAI-EDIT] Uploading generated image to Cloudinary...");
            String url = imageUploadService.uploadBase64(b64, "image/png");
            System.err.println("[OPENAI-EDIT] Cloudinary URL: " + url);
            return url;

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Image generation with bytes failed: " + e.getMessage(), e);
        }
    }

    // Responses API: gpt-4o + image_generation tool — same model path as ChatGPT.com.
    // Sends the fabric image + prompt as a chat turn; the model generates and returns the image.
    public String generateImageWithResponsesAPI(byte[] imageBytes, String contentType, String prompt) {
        try {
            String mimeType = contentType != null ? contentType : "image/jpeg";
            String base64   = java.util.Base64.getEncoder().encodeToString(imageBytes);

            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", "gpt-4o");

            // User message: image first, then the instruction
            ArrayNode input = body.putArray("input");
            ObjectNode userMsg = input.addObject();
            userMsg.put("role", "user");
            ArrayNode content = userMsg.putArray("content");

            ObjectNode imageNode = content.addObject();
            imageNode.put("type", "input_image");
            imageNode.put("image_url", "data:" + mimeType + ";base64," + base64);

            ObjectNode textNode = content.addObject();
            textNode.put("type", "input_text");
            textNode.put("text", prompt);

            // Image generation tool
            ArrayNode tools = body.putArray("tools");
            ObjectNode tool = tools.addObject();
            tool.put("type", "image_generation");
            tool.put("quality", "high");
            tool.put("size", "1024x1024");

            System.err.println("[OPENAI-RESP] Sending to /v1/responses (gpt-4o + image_generation)"
                + " image=" + kb(imageBytes.length) + " KB");
            System.err.println("[OPENAI-RESP] Prompt: " + prompt);

            HttpResponse<String> response = post(responsesUrl, body, 180);
            System.err.println("[OPENAI-RESP] HTTP status: " + response.statusCode());

            if (response.statusCode() != 200) {
                throw new RuntimeException("Responses API error " + response.statusCode() + ": " + response.body());
            }

            // Log first 1000 chars of response for debugging
            String bodyStr = response.body();
            System.err.println("[OPENAI-RESP] Response (first 1000 chars): "
                + bodyStr.substring(0, Math.min(1000, bodyStr.length())));

            // Navigate: output[] → type=="image_generation_call" → result (base64)
            JsonNode respJson = objectMapper.readTree(bodyStr);
            for (JsonNode item : respJson.path("output")) {
                if ("image_generation_call".equals(item.path("type").asText())) {
                    String imageData = item.path("result").asText();
                    if (!imageData.isBlank()) {
                        System.err.println("[OPENAI-RESP] Image generated, uploading to Cloudinary");
                        return imageUploadService.uploadBase64(imageData, "image/png");
                    }
                }
            }

            throw new RuntimeException("No image_generation_call found in response. Full body: " + bodyStr);

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Responses API generation failed: " + e.getMessage(), e);
        }
    }

    // Reference-based generation: downloads the fabric image and sends it to /v1/images/edits
    // so the model sees the actual fabric and preserves its exact color, pattern, and texture.
    public String generateImageWithReference(String referenceImageUrl, String prompt, String quality) {
        try {
            System.err.println("[OPENAI-EDIT] Downloading reference image: " + referenceImageUrl);
            HttpResponse<byte[]> imgResp = httpClient.send(
                HttpRequest.newBuilder().uri(URI.create(referenceImageUrl))
                    .timeout(Duration.ofSeconds(30)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
            if (imgResp.statusCode() != 200)
                throw new RuntimeException("Could not download reference image: HTTP " + imgResp.statusCode());

            byte[] imageBytes = imgResp.body();
            String imgType = imgResp.headers().firstValue("content-type")
                .orElse("image/jpeg").replaceAll(";.*", "").trim();
            String ext = imgType.contains("png") ? "png" : "jpg";
            logImageDimensions("[OPENAI-EDIT] Downloaded reference image (type=" + imgType + ")", imageBytes, imgType);

            String boundary = "----OpenAIBoundary" + Long.toHexString(System.currentTimeMillis());
            byte[] multipart = buildEditMultipart(boundary, imageBytes, imgType, ext, prompt, quality);

            System.err.println("[OPENAI-EDIT] ══ REQUEST (URL-based) ════════════════════════════");
            System.err.println("[OPENAI-EDIT]   endpoint      : " + imageEditUrl);
            System.err.println("[OPENAI-EDIT]   model         : gpt-image-1");
            System.err.println("[OPENAI-EDIT]   quality       : " + quality);
            System.err.println("[OPENAI-EDIT]   size          : 1024x1024");
            System.err.println("[OPENAI-EDIT]   image type    : " + imgType);
            System.err.println("[OPENAI-EDIT]   image size    : " + kb(imageBytes.length) + " KB");
            System.err.println("[OPENAI-EDIT]   multipart     : " + kb(multipart.length) + " KB");
            System.err.println("[OPENAI-EDIT]   prompt        : " + prompt);
            System.err.println("[OPENAI-EDIT] ══════════════════════════════════════════════════");

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(imageEditUrl))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipart))
                .timeout(Duration.ofSeconds(240))
                .build();

            long t0 = System.currentTimeMillis();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long elapsed = System.currentTimeMillis() - t0;

            System.err.println("[OPENAI-EDIT] ══ RESPONSE ══════════════════════════════════════");
            System.err.println("[OPENAI-EDIT]   http status   : " + response.statusCode());
            System.err.println("[OPENAI-EDIT]   elapsed ms    : " + elapsed);
            String bodyStr = response.body();
            System.err.println("[OPENAI-EDIT]   body (2000)   : "
                + bodyStr.substring(0, Math.min(2000, bodyStr.length())));
            System.err.println("[OPENAI-EDIT] ══════════════════════════════════════════════════");

            if (response.statusCode() != 200)
                throw new RuntimeException("Image edit error " + response.statusCode() + ": " + bodyStr);

            JsonNode respJson = objectMapper.readTree(bodyStr);
            JsonNode first = respJson.path("data").get(0);
            String revisedPrompt = first.path("revised_prompt").asText("");
            if (!revisedPrompt.isBlank())
                System.err.println("[OPENAI-EDIT]   revised_prompt: " + revisedPrompt);

            String b64 = first.path("b64_json").asText();
            System.err.println("[OPENAI-EDIT]   b64_json len  : " + b64.length() + " chars (~" + (b64.length() * 3L / 4 / 1024) + " KB)");

            System.err.println("[OPENAI-EDIT] Uploading generated image to Cloudinary...");
            String url = imageUploadService.uploadBase64(b64, "image/png");
            System.err.println("[OPENAI-EDIT] Cloudinary URL: " + url);
            return url;

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Image generation with reference failed: " + e.getMessage(), e);
        }
    }

    private byte[] buildEditMultipart(String boundary, byte[] imageBytes, String contentType,
                                       String ext, String prompt, String quality) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        addFilePart(out, boundary, "image", "fabric." + ext, contentType, imageBytes);
        addTextPart(out, boundary, "model", "gpt-image-1");
        addTextPart(out, boundary, "prompt", prompt);
        addTextPart(out, boundary, "n", "1");
        addTextPart(out, boundary, "size", "1024x1024");
        addTextPart(out, boundary, "quality", "high");
        out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private void logImageDimensions(String label, byte[] imageBytes, String mime) {
        try {
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(
                new java.io.ByteArrayInputStream(imageBytes));
            if (img != null) {
                System.err.println(label + ": " + img.getWidth() + "x" + img.getHeight()
                    + " px, " + kb(imageBytes.length) + " KB, type=" + mime);
            } else {
                System.err.println(label + ": " + kb(imageBytes.length) + " KB (unreadable dims), type=" + mime);
            }
        } catch (Exception e) {
            System.err.println(label + ": " + kb(imageBytes.length) + " KB, type=" + mime
                + " (parse error: " + e.getMessage() + ")");
        }
    }

    private static String kb(long bytes) {
        return String.format("%.1f", bytes / 1024.0);
    }

    private void addFilePart(ByteArrayOutputStream out, String boundary, String name,
                              String filename, String contentType, byte[] data) throws Exception {
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + filename + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(data);
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private void addTextPart(ByteArrayOutputStream out, String boundary, String name, String value) throws Exception {
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(value.getBytes(StandardCharsets.UTF_8));
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private String generateImage(String prompt, String quality) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", "gpt-image-1");
            body.put("prompt", prompt);
            body.put("n", 1);
            body.put("size", "1024x1024");
            body.put("quality", quality);

            System.err.println("[OPENAI-GEN] ══ REQUEST ══════════════════════════════════════");
            System.err.println("[OPENAI-GEN]   endpoint : " + imageUrl);
            System.err.println("[OPENAI-GEN]   model    : gpt-image-1");
            System.err.println("[OPENAI-GEN]   quality  : " + quality);
            System.err.println("[OPENAI-GEN]   prompt   : " + prompt);
            System.err.println("[OPENAI-GEN] ══════════════════════════════════════════════════");

            long t0 = System.currentTimeMillis();
            HttpResponse<String> response = post(imageUrl, body, 120);
            long elapsed = System.currentTimeMillis() - t0;

            System.err.println("[OPENAI-GEN] http status=" + response.statusCode() + " elapsed=" + elapsed + "ms");
            if (response.statusCode() != 200)
                throw new RuntimeException("Image generation error " + response.statusCode() + ": " + response.body());

            JsonNode respJson = objectMapper.readTree(response.body());
            String b64 = respJson.path("data").get(0).path("b64_json").asText();
            System.err.println("[OPENAI-GEN] b64_json len=" + b64.length() + " chars (~" + (b64.length() * 3L / 4 / 1024) + " KB)");

            return imageUploadService.uploadBase64(b64, "image/png");

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Image generation failed: " + e.getMessage(), e);
        }
    }

    public String buildFlatLayPrompt(String fabricDescription, String productName, String category) {
        return String.format(
            "Professional product photography flat lay of Indian %s fabric. " +
            "Fabric folded neatly on a clean white marble surface with subtle grey veining. " +
            "Minimal elegant styling: one small sprig of white jasmine as accent. " +
            "Perfectly even soft overhead studio lighting, subtle diffused shadows. " +
            "Fabric details visible: %s. Product: %s. " +
            "No mannequin, no person, fabric only.",
            category != null && !category.isBlank() ? category : "textile",
            fabricDescription,
            productName != null ? productName : "fabric"
        );
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

    public String[] classifyGarmentType(String imageUrl) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", "gpt-4o");
            body.put("max_tokens", 120);

            ArrayNode messages = body.putArray("messages");
            ObjectNode message = messages.addObject();
            message.put("role", "user");
            ArrayNode content = message.putArray("content");

            ObjectNode img = content.addObject();
            img.put("type", "image_url");
            img.putObject("image_url").put("url", imageUrl).put("detail", "low");

            ObjectNode text = content.addObject();
            text.put("type", "text");
            text.put("text",
                "What type of Indian fashion item is shown in this image? " +
                "Reply with exactly two lines:\n" +
                "Line 1: one word from [Saree, Kurti, Salwar, Blouse, Frock, Lehenga, Dupatta, Fabric, Accessories, Other]\n" +
                "Line 2: one sentence describing the fabric color, texture, and pattern."
            );

            HttpResponse<String> response = post(visionUrl, body, 30);
            if (response.statusCode() != 200)
                throw new RuntimeException("GPT-4o error " + response.statusCode());

            String raw = objectMapper.readTree(response.body())
                .path("choices").get(0).path("message").path("content").asText().trim();

            String[] lines = raw.split("\\r?\\n", 2);
            String garmentType = normalizeGarmentType(lines[0].trim().replaceAll("[^a-zA-Z]", ""));
            String description = lines.length > 1 ? lines[1].trim() : raw;
            return new String[]{garmentType, description};

        } catch (Exception e) {
            System.err.println("[CLASSIFY] Garment classification failed: " + e.getMessage());
            return new String[]{"Fabric", "Indian fabric or garment"};
        }
    }

    private String normalizeGarmentType(String raw) {
        String lower = raw.toLowerCase();
        if (lower.contains("saree") || lower.contains("sari"))   return "Saree";
        if (lower.contains("kurti") || lower.contains("kurta"))  return "Kurti";
        if (lower.contains("salwar"))                            return "Salwar";
        if (lower.contains("blouse"))                            return "Blouse";
        if (lower.contains("frock")  || lower.contains("dress")) return "Frock";
        if (lower.contains("lehenga"))                           return "Lehenga";
        if (lower.contains("dupatta"))                           return "Dupatta";
        if (lower.contains("fabric") || lower.contains("cloth") || lower.contains("textile")) return "Fabric";
        if (lower.contains("access"))                            return "Accessories";
        return "Other";
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

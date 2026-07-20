package com.example.mybill.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.logging.Logger;

@Service
public class ShotstackService {

    @Value("${shotstack.api.key}") private String apiKey;
    @Value("${shotstack.base.url}") private String baseUrl;

    private static final Logger log = Logger.getLogger(ShotstackService.class.getName());
    private final ObjectMapper mapper = new ObjectMapper();
    private final RestTemplate rest = new RestTemplate();

    private static final int SLIDE_DURATION_SEC = 3;
    private static final int POLL_INTERVAL_MS  = 5000;
    private static final int MAX_POLLS         = 60; // 5 min max

    /**
     * Renders a 9:16 MP4 slideshow from the given Cloudinary image URLs.
     * Blocks until Shotstack finishes (or throws if it times out / fails).
     *
     * @return public MP4 URL
     */
    public String renderSlideshow(List<String> imageUrls,
                                  String productName,
                                  BigDecimal price) throws Exception {

        String renderId = submitRender(imageUrls, productName, price);
        log.info("[Shotstack] Render submitted: " + renderId);
        return pollForUrl(renderId);
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private String submitRender(List<String> imageUrls,
                                String productName,
                                BigDecimal price) throws Exception {

        ObjectNode payload = buildPayload(imageUrls, productName, price);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-api-key", apiKey);

        ResponseEntity<String> response = rest.postForEntity(
            baseUrl + "/render",
            new HttpEntity<>(mapper.writeValueAsString(payload), headers),
            String.class
        );

        JsonNode body = mapper.readTree(response.getBody());
        if (!body.path("success").asBoolean()) {
            throw new RuntimeException("Shotstack submit failed: " + response.getBody());
        }
        return body.path("response").path("id").asText();
    }

    private String pollForUrl(String renderId) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set("x-api-key", apiKey);
        HttpEntity<Void> req = new HttpEntity<>(headers);

        for (int i = 0; i < MAX_POLLS; i++) {
            Thread.sleep(POLL_INTERVAL_MS);

            ResponseEntity<String> response = rest.exchange(
                baseUrl + "/render/" + renderId,
                HttpMethod.GET, req, String.class
            );

            JsonNode body = mapper.readTree(response.getBody());
            String status = body.path("response").path("status").asText();
            log.info("[Shotstack] Poll " + (i + 1) + " status=" + status);

            switch (status) {
                case "done" -> { return body.path("response").path("url").asText(); }
                case "failed" -> throw new RuntimeException(
                    "Shotstack render failed: " + body.path("response").path("error").asText());
            }
        }
        throw new RuntimeException("Shotstack render timed out after " + (MAX_POLLS * POLL_INTERVAL_MS / 1000) + "s");
    }

    private ObjectNode buildPayload(List<String> imageUrls,
                                    String productName,
                                    BigDecimal price) {

        int totalDuration = imageUrls.size() * SLIDE_DURATION_SEC;

        // ── image track ─────────────────────────────────────────────────────
        ArrayNode imageClips = mapper.createArrayNode();
        for (int i = 0; i < imageUrls.size(); i++) {
            ObjectNode asset = mapper.createObjectNode();
            asset.put("type", "image");
            asset.put("src", imageUrls.get(i));

            ObjectNode clip = mapper.createObjectNode();
            clip.set("asset", asset);
            clip.put("start", i * SLIDE_DURATION_SEC);
            clip.put("length", SLIDE_DURATION_SEC);
            clip.put("effect", i % 2 == 0 ? "zoomIn" : "zoomOut");

            ObjectNode transition = mapper.createObjectNode();
            transition.put("in", "fade");
            transition.put("out", "fade");
            clip.set("transition", transition);

            imageClips.add(clip);
        }

        ObjectNode imageTrack = mapper.createObjectNode();
        imageTrack.set("clips", imageClips);

        // ── product name overlay (top) ───────────────────────────────────────
        String nameHtml = "<p>" + escapeHtml(productName) + "</p>";
        String nameCss  = "p{color:#fff;font-size:40px;font-weight:700;text-align:center;"
                        + "text-shadow:0 2px 8px rgba(0,0,0,0.7);margin:0;padding:16px 24px;"
                        + "background:linear-gradient(transparent,rgba(0,0,0,0.5));}";

        ObjectNode nameAsset = mapper.createObjectNode();
        nameAsset.put("type", "html");
        nameAsset.put("html", nameHtml);
        nameAsset.put("css", nameCss);
        nameAsset.put("width", 540);
        nameAsset.put("height", 120);

        ObjectNode nameClip = mapper.createObjectNode();
        nameClip.set("asset", nameAsset);
        nameClip.put("start", 0);
        nameClip.put("length", totalDuration);
        nameClip.put("position", "top");

        ObjectNode nameTrack = mapper.createObjectNode();
        nameTrack.set("clips", mapper.createArrayNode().add(nameClip));

        // ── price overlay (bottom) ──────────────────────────────────────────
        String priceText = price != null ? "₹" + price.toPlainString() : "";
        String priceHtml = "<p>" + priceText + "</p>";
        String priceCss  = "p{color:#fff;font-size:44px;font-weight:800;text-align:center;"
                        + "text-shadow:0 2px 8px rgba(0,0,0,0.8);margin:0;padding:16px 24px;"
                        + "background:linear-gradient(rgba(0,0,0,0.5),transparent);}";

        ObjectNode priceAsset = mapper.createObjectNode();
        priceAsset.put("type", "html");
        priceAsset.put("html", priceHtml);
        priceAsset.put("css", priceCss);
        priceAsset.put("width", 540);
        priceAsset.put("height", 120);

        ObjectNode priceClip = mapper.createObjectNode();
        priceClip.set("asset", priceAsset);
        priceClip.put("start", 0);
        priceClip.put("length", totalDuration);
        priceClip.put("position", "bottom");

        ObjectNode priceTrack = mapper.createObjectNode();
        priceTrack.set("clips", mapper.createArrayNode().add(priceClip));

        // ── timeline ────────────────────────────────────────────────────────
        ObjectNode timeline = mapper.createObjectNode();
        ArrayNode tracks = mapper.createArrayNode();
        tracks.add(nameTrack);
        tracks.add(priceTrack);
        tracks.add(imageTrack);
        timeline.set("tracks", tracks);

        // ── output: 9:16 vertical HD ────────────────────────────────────────
        ObjectNode output = mapper.createObjectNode();
        output.put("format", "mp4");
        output.put("resolution", "hd");
        output.put("aspectRatio", "9:16");
        output.put("fps", 25);

        ObjectNode root = mapper.createObjectNode();
        root.set("timeline", timeline);
        root.set("output", output);
        return root;
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}

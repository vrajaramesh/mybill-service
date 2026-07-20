package com.example.mybill.service;

import com.example.mybill.multitenancy.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

@Service
public class InstagramReelsService {

    @Value("${instagram.user.id}")       private String igUserId;
    @Value("${instagram.graph.url}")     private String graphUrl;
    @Value("${meta.access.token:}")      private String accessToken;

    @Autowired private ProductService productService;
    @Autowired private ProductImageRepository productImageRepository;
    @Autowired private ProductMarketingContentRepository marketingContentRepo;
    @Autowired private ShotstackService shotstackService;

    private static final Logger log = Logger.getLogger(InstagramReelsService.class.getName());
    private final ObjectMapper mapper = new ObjectMapper();
    private final RestTemplate rest = new RestTemplate();

    // In-memory job tracker: jobId → ReelsJobStatus
    private final ConcurrentHashMap<String, ReelsJobStatus> jobs = new ConcurrentHashMap<>();

    // ── Public API ────────────────────────────────────────────────────────────

    public record ReelsJobStatus(
        String jobId,
        String status,       // queued | rendering | uploading | publishing | done | failed
        String message,
        String instagramPostId,
        String videoUrl
    ) {}

    /**
     * Starts async Reels creation. Returns a jobId immediately.
     */
    public String publish(List<Integer> productIds, String schema) {
        String jobId = UUID.randomUUID().toString();
        jobs.put(jobId, new ReelsJobStatus(jobId, "queued", "Job queued", null, null));
        publishAsync(jobId, productIds, schema);
        return jobId;
    }

    public Optional<ReelsJobStatus> getStatus(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    // ── Async orchestration ───────────────────────────────────────────────────

    @Async
    public void publishAsync(String jobId, List<Integer> productIds, String schema) {
        TenantContext.setCurrentTenant(schema);
        try {
            // 1. Collect images + build caption from first product
            List<String> imageUrls = new ArrayList<>();
            String productName    = "New Arrival";
            java.math.BigDecimal price = null;
            String caption        = null;

            for (Integer pid : productIds) {
                Optional<Product> opt = productService.getProductById(pid);
                if (opt.isEmpty()) continue;

                Product p = opt.get();
                if (productName.equals("New Arrival")) {
                    productName = p.getProductName();
                    price       = p.getSellingPrice();
                }

                // Cloudinary images only (publicly accessible)
                productImageRepository
                    .findByProductProductIdOrderBySortOrderAscCreatedAtAsc(pid)
                    .stream()
                    .filter(i -> !"video".equals(i.getMediaType()))
                    .filter(i -> i.getImageUrl() != null && i.getImageUrl().startsWith("https://res.cloudinary.com/"))
                    .map(ProductImage::getImageUrl)
                    .forEach(imageUrls::add);

                // Caption from Hermes (first product that has one)
                if (caption == null) {
                    marketingContentRepo.findById(pid).ifPresent(mc -> {
                        // nothing to assign here — handled below
                    });
                    caption = marketingContentRepo.findById(pid)
                        .map(mc -> buildCaption(mc.getInstagramCaption(), mc.getHashtags()))
                        .orElse(null);
                }
            }

            if (imageUrls.isEmpty()) {
                fail(jobId, "No Cloudinary images found for the given products");
                return;
            }

            if (caption == null) {
                caption = productName + "\n\n#srisafabrics #fashion #fabric #saree";
            }

            // 2. Render video via Shotstack
            update(jobId, "rendering", "Rendering slideshow video (" + imageUrls.size() + " images)...", null, null);
            String videoUrl = shotstackService.renderSlideshow(imageUrls, productName, price);
            log.info("[Reels] Video rendered: " + videoUrl);

            // 3. Create Instagram media container
            update(jobId, "uploading", "Uploading to Instagram...", null, videoUrl);
            String containerId = createContainer(videoUrl, caption);
            log.info("[Reels] Container created: " + containerId);

            // 4. Wait for container to be ready
            waitForContainer(containerId);

            // 5. Publish
            update(jobId, "publishing", "Publishing Reel...", null, videoUrl);
            String postId = publishContainer(containerId);
            log.info("[Reels] Published! Post ID: " + postId);

            jobs.put(jobId, new ReelsJobStatus(jobId, "done", "Reel published successfully", postId, videoUrl));

        } catch (Exception e) {
            log.warning("[Reels] Job " + jobId + " failed: " + e.getMessage());
            fail(jobId, e.getMessage());
        } finally {
            TenantContext.clear();
        }
    }

    // ── Instagram Graph API ───────────────────────────────────────────────────

    private String createContainer(String videoUrl, String caption) throws Exception {
        String url = UriComponentsBuilder
            .fromHttpUrl(graphUrl + "/" + igUserId + "/media")
            .queryParam("media_type", "REELS")
            .queryParam("video_url", videoUrl)
            .queryParam("caption", caption)
            .queryParam("share_to_feed", "true")
            .queryParam("access_token", accessToken)
            .build(false).toUriString();

        ResponseEntity<String> response = rest.postForEntity(url, null, String.class);
        JsonNode body = mapper.readTree(response.getBody());

        if (body.has("error")) {
            throw new RuntimeException("Instagram container error: " + body.path("error").path("message").asText());
        }
        return body.path("id").asText();
    }

    private void waitForContainer(String containerId) throws Exception {
        for (int i = 0; i < 24; i++) { // max 2 min
            Thread.sleep(5000);

            String url = UriComponentsBuilder
                .fromHttpUrl(graphUrl + "/" + containerId)
                .queryParam("fields", "status_code,status")
                .queryParam("access_token", accessToken)
                .build(false).toUriString();

            ResponseEntity<String> response = rest.getForEntity(url, String.class);
            JsonNode body = mapper.readTree(response.getBody());
            String statusCode = body.path("status_code").asText();
            log.info("[Reels] Container " + containerId + " status: " + statusCode);

            if ("FINISHED".equals(statusCode)) return;
            if ("ERROR".equals(statusCode) || "EXPIRED".equals(statusCode)) {
                throw new RuntimeException("Instagram container status: " + statusCode
                    + " — " + body.path("status").asText());
            }
        }
        throw new RuntimeException("Instagram container timed out waiting for FINISHED status");
    }

    private String publishContainer(String containerId) throws Exception {
        String url = UriComponentsBuilder
            .fromHttpUrl(graphUrl + "/" + igUserId + "/media_publish")
            .queryParam("creation_id", containerId)
            .queryParam("access_token", accessToken)
            .build(false).toUriString();

        ResponseEntity<String> response = rest.postForEntity(url, null, String.class);
        JsonNode body = mapper.readTree(response.getBody());

        if (body.has("error")) {
            throw new RuntimeException("Instagram publish error: " + body.path("error").path("message").asText());
        }
        return body.path("id").asText();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String buildCaption(String instagramCaption, String hashtags) {
        StringBuilder sb = new StringBuilder();
        if (instagramCaption != null && !instagramCaption.isBlank()) sb.append(instagramCaption.trim());
        if (hashtags != null && !hashtags.isBlank()) {
            if (!sb.isEmpty()) sb.append("\n\n");
            sb.append(hashtags.trim());
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private void update(String jobId, String status, String message, String postId, String videoUrl) {
        jobs.put(jobId, new ReelsJobStatus(jobId, status, message, postId, videoUrl));
    }

    private void fail(String jobId, String message) {
        ReelsJobStatus current = jobs.getOrDefault(jobId, new ReelsJobStatus(jobId, "failed", message, null, null));
        jobs.put(jobId, new ReelsJobStatus(jobId, "failed", message, null, current.videoUrl()));
    }
}

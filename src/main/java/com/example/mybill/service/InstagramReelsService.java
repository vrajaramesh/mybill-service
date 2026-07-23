package com.example.mybill.service;

import com.example.mybill.multitenancy.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

@Service
public class InstagramReelsService {

    @Value("${instagram.user.id}")    private String igUserId;
    @Value("${instagram.graph.url}")  private String graphUrl;
    @Value("${meta.access.token:}")   private String accessToken;

    @Autowired private ProductService productService;
    @Autowired private ProductMarketingContentRepository marketingContentRepo;
    @Autowired private FFmpegVideoService ffmpegVideoService;
    @Autowired private HashtagGeneratorService hashtagGeneratorService;

    private static final Logger log = Logger.getLogger(InstagramReelsService.class.getName());
    private final ObjectMapper mapper = new ObjectMapper();
    private final RestTemplate rest   = new RestTemplate();

    private final ConcurrentHashMap<String, ReelsJobStatus> jobs = new ConcurrentHashMap<>();

    // ── Public API ────────────────────────────────────────────────────────────

    public record ReelsJobStatus(
        String jobId,
        String status,         // queued | rendering | uploading | publishing | done | failed
        String message,
        String instagramPostId,
        String videoUrl
    ) {}

    /**
     * Starts async Reel creation from explicit image URLs + a product for caption.
     * Returns a jobId immediately.
     */
    public String publish(Integer productId, List<String> imageUrls, String title, String schema) {
        String jobId = UUID.randomUUID().toString();
        jobs.put(jobId, new ReelsJobStatus(jobId, "queued", "Job queued", null, null));
        publishAsync(jobId, productId, imageUrls, title, schema);
        return jobId;
    }

    public Optional<ReelsJobStatus> getStatus(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    public Map<String, Object> debugTokenAccess() throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("configuredIgUserId", igUserId);

        // Pages
        String pagesUrl = UriComponentsBuilder
            .fromHttpUrl(graphUrl + "/me/accounts")
            .queryParam("access_token", accessToken)
            .build(false).toUriString();

        JsonNode pages = mapper.readTree(rest.getForEntity(pagesUrl, String.class).getBody());
        if (pages.has("error")) {
            result.put("pagesError", pages.path("error").path("message").asText());
            return result;
        }

        List<Map<String, Object>> pagesList = new ArrayList<>();
        for (JsonNode page : pages.path("data")) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("pageId",   page.path("id").asText());
            p.put("pageName", page.path("name").asText());

            String igCheckUrl = UriComponentsBuilder
                .fromHttpUrl(graphUrl + "/" + page.path("id").asText())
                .queryParam("fields", "instagram_business_account")
                .queryParam("access_token", accessToken)
                .build(false).toUriString();

            JsonNode pageData = mapper.readTree(rest.getForEntity(igCheckUrl, String.class).getBody());
            JsonNode igAcc = pageData.path("instagram_business_account");
            p.put("instagramBusinessAccountId", igAcc.has("id") ? igAcc.path("id").asText() : null);
            pagesList.add(p);
        }
        result.put("pages", pagesList);
        return result;
    }

    // ── Async orchestration ───────────────────────────────────────────────────

    @Async
    public void publishAsync(String jobId, Integer productId, List<String> imageUrls, String title, String schema) {
        TenantContext.setCurrentTenant(schema);
        try {
            // 1. Build caption — use user-supplied title if provided, else Hermes auto-caption
            String productName = "New Arrival";
            Optional<Product> productOpt = productService.getProductById(productId);
            if (productOpt.isPresent()) productName = productOpt.get().getProductName();

            String baseCaption;
            if (title != null && !title.isBlank()) {
                baseCaption = title;
            } else {
                baseCaption = marketingContentRepo.findById(productId)
                    .map(mc -> buildCaption(mc.getInstagramCaption(), mc.getHashtags()))
                    .orElse(productName);
            }
            Product product = productOpt.orElse(null);
            String dynamicTags = hashtagGeneratorService.generateHashtags(product, title);
            String caption = baseCaption.isBlank() ? dynamicTags : baseCaption + "\n\n" + dynamicTags;
            log.info("[Reels] Caption to post: " + caption.substring(0, Math.min(200, caption.length())));

            // 2. Render video via FFmpeg (1080x1920, Ken Burns + music)
            update(jobId, "rendering", "Rendering Reel with " + imageUrls.size() + " image(s)...", null, null);
            String videoUrl = ffmpegVideoService.generateSlideshow(imageUrls, productName);
            log.info("[Reels] Video rendered: " + videoUrl);

            // 3. Create Instagram media container (resolves Page Access Token once)
            update(jobId, "uploading", "Uploading video to Instagram...", null, videoUrl);
            IgContext ctx = resolveIgContext();
            String containerId = createContainer(videoUrl, caption, ctx);
            log.info("[Reels] Container created: " + containerId);

            // 4. Wait for container FINISHED
            waitForContainer(containerId, ctx);

            // 5. Publish
            update(jobId, "publishing", "Publishing Reel...", null, videoUrl);
            String postId = publishContainer(containerId, ctx);
            log.info("[Reels] Published! Post ID: " + postId);

            jobs.put(jobId, new ReelsJobStatus(jobId, "done", "Reel published successfully!", postId, videoUrl));

        } catch (Exception e) {
            log.warning("[Reels] Job " + jobId + " failed: " + e.getMessage());
            fail(jobId, e.getMessage());
        } finally {
            TenantContext.clear();
        }
    }

    // ── Instagram Graph API ───────────────────────────────────────────────────

    private record IgContext(String igUserId, String pageToken) {}

    /**
     * Resolves the Instagram Business Account ID AND the Page Access Token from /me/accounts.
     * Instagram publishing requires the Page Access Token, not the User/System token directly.
     */
    private IgContext resolveIgContext() throws Exception {
        String pagesUrl = UriComponentsBuilder
            .fromHttpUrl(graphUrl + "/me/accounts")
            .queryParam("access_token", accessToken)
            .build(false).toUriString();

        JsonNode pages = mapper.readTree(rest.getForEntity(pagesUrl, String.class).getBody());
        log.info("[Reels] Pages response: " + pages.toString().substring(0, Math.min(300, pages.toString().length())));

        if (pages.has("error"))
            throw new RuntimeException("Could not fetch pages: " + pages.path("error").path("message").asText());

        JsonNode pageList = pages.path("data");
        if (!pageList.isArray() || pageList.isEmpty())
            throw new RuntimeException("No Facebook Pages found for this token.");

        for (JsonNode page : pageList) {
            String pageId        = page.path("id").asText();
            String pageToken     = page.path("access_token").asText();

            String igCheckUrl = UriComponentsBuilder
                .fromHttpUrl(graphUrl + "/" + pageId)
                .queryParam("fields", "instagram_business_account")
                .queryParam("access_token", pageToken)
                .build(false).toUriString();

            JsonNode pageData  = mapper.readTree(rest.getForEntity(igCheckUrl, String.class).getBody());
            JsonNode igAccount = pageData.path("instagram_business_account");
            if (!igAccount.isMissingNode() && igAccount.has("id")) {
                String igId = igAccount.path("id").asText();
                log.info("[Reels] Resolved ig-user-id=" + igId + " pageToken present=" + !pageToken.isBlank());
                return new IgContext(igId, pageToken);
            }
        }

        log.warning("[Reels] No linked Instagram Business Account found. Falling back to config.");
        return new IgContext(igUserId, accessToken);
    }

    private String createContainer(String videoUrl, String caption, IgContext ctx) throws Exception {
        // Instagram requires POST params in the request body (form-encoded), not as URL query params
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("media_type",    "REELS");
        form.add("video_url",     videoUrl);
        form.add("caption",       caption != null ? caption : "");
        form.add("share_to_feed", "true");
        form.add("access_token",  ctx.pageToken());

        String endpoint = graphUrl + "/" + ctx.igUserId() + "/media";
        ResponseEntity<String> response = rest.postForEntity(endpoint, new HttpEntity<>(form, headers), String.class);
        JsonNode body = mapper.readTree(response.getBody());
        if (body.has("error"))
            throw new RuntimeException("Instagram container error: " + body.path("error").path("message").asText());
        return body.path("id").asText();
    }

    private void waitForContainer(String containerId, IgContext ctx) throws Exception {
        for (int i = 0; i < 24; i++) {
            Thread.sleep(5000);
            String url = UriComponentsBuilder
                .fromHttpUrl(graphUrl + "/" + containerId)
                .queryParam("fields", "status_code,status")
                .queryParam("access_token", ctx.pageToken())
                .build(true).toUriString();

            JsonNode body = mapper.readTree(rest.getForEntity(url, String.class).getBody());
            String statusCode = body.path("status_code").asText();
            log.info("[Reels] Container " + containerId + " status: " + statusCode);
            if ("FINISHED".equals(statusCode)) return;
            if ("ERROR".equals(statusCode) || "EXPIRED".equals(statusCode))
                throw new RuntimeException("Instagram container status: " + statusCode + " — " + body.path("status").asText());
        }
        throw new RuntimeException("Instagram container timed out");
    }

    private String publishContainer(String containerId, IgContext ctx) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("creation_id",  containerId);
        form.add("access_token", ctx.pageToken());

        String endpoint = graphUrl + "/" + ctx.igUserId() + "/media_publish";
        JsonNode body = mapper.readTree(rest.postForEntity(endpoint, new HttpEntity<>(form, headers), String.class).getBody());
        if (body.has("error"))
            throw new RuntimeException("Instagram publish error: " + body.path("error").path("message").asText());
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
        ReelsJobStatus cur = jobs.getOrDefault(jobId, new ReelsJobStatus(jobId, "failed", message, null, null));
        jobs.put(jobId, new ReelsJobStatus(jobId, "failed", message, null, cur.videoUrl()));
    }
}

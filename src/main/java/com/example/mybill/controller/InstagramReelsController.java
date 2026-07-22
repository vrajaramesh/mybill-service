package com.example.mybill.controller;

import com.example.mybill.multitenancy.TenantContext;
import com.example.mybill.service.InstagramReelsService;
import com.example.mybill.service.InstagramReelsService.ReelsJobStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/instagram/reels")
public class InstagramReelsController {

    @Autowired private InstagramReelsService reelsService;

    /**
     * POST /api/instagram/reels/publish
     * Body: { "productId": 6166, "imageUrls": ["https://...", ...] }
     * Returns immediately with a jobId. Poll /status/{jobId} for progress.
     */
    @PostMapping("/publish")
    public ResponseEntity<?> publish(@RequestBody Map<String, Object> body) {
        Object rawId   = body.get("productId");
        Object rawUrls = body.get("imageUrls");

        if (rawId == null || rawUrls == null)
            return ResponseEntity.badRequest().body(Map.of("error", "productId and imageUrls are required"));

        Integer productId;
        List<String> imageUrls;
        try {
            productId = Integer.parseInt(rawId.toString());
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) rawUrls;
            imageUrls = list.stream().map(Object::toString).toList();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid productId or imageUrls"));
        }

        if (imageUrls.isEmpty() || imageUrls.size() > 5)
            return ResponseEntity.badRequest().body(Map.of("error", "Select 1 to 5 images"));

        String schema = TenantContext.getCurrentTenant();
        String jobId  = reelsService.publish(productId, imageUrls, schema);

        return ResponseEntity.accepted().body(Map.of(
            "jobId",      jobId,
            "status",     "queued",
            "message",    "Reel creation started with " + imageUrls.size() + " image(s)",
            "statusUrl",  "/api/instagram/reels/status/" + jobId
        ));
    }

    /**
     * GET /api/instagram/reels/status/{jobId}
     * Returns: { jobId, status, message, instagramPostId, videoUrl }
     */
    @GetMapping("/status/{jobId}")
    public ResponseEntity<?> status(@PathVariable String jobId) {
        return reelsService.getStatus(jobId)
            .map(s -> ResponseEntity.ok(Map.of(
                "jobId",           s.jobId(),
                "status",          s.status(),
                "message",         s.message(),
                "instagramPostId", s.instagramPostId() != null ? s.instagramPostId() : "",
                "videoUrl",        s.videoUrl()        != null ? s.videoUrl()        : ""
            )))
            .orElse(ResponseEntity.notFound().build());
    }
}

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
     * Start an async Reels publish job.
     *
     * POST /api/instagram/reels/publish
     * Body: { "productIds": [6166, 6167] }
     *
     * Returns immediately with a jobId. Poll /status/{jobId} for progress.
     */
    @PostMapping("/publish")
    public ResponseEntity<?> publish(@RequestBody Map<String, Object> body) {
        Object raw = body.get("productIds");
        if (raw == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "productIds is required"));
        }

        List<Integer> productIds;
        try {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) raw;
            productIds = list.stream()
                .map(o -> Integer.parseInt(o.toString()))
                .toList();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "productIds must be a list of integers"));
        }

        if (productIds.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "productIds must not be empty"));
        }

        String schema = TenantContext.getCurrentTenant();
        String jobId  = reelsService.publish(productIds, schema);

        return ResponseEntity.accepted().body(Map.of(
            "jobId",      jobId,
            "status",     "queued",
            "message",    "Reel creation started for " + productIds.size() + " product(s)",
            "productIds", productIds,
            "statusUrl",  "/api/instagram/reels/status/" + jobId
        ));
    }

    /**
     * Poll job status.
     *
     * GET /api/instagram/reels/status/{jobId}
     * Returns: { jobId, status, message, instagramPostId, videoUrl }
     * status values: queued | rendering | uploading | publishing | done | failed
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

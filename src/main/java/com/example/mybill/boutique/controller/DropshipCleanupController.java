package com.example.mybill.boutique.controller;

import com.example.mybill.boutique.config.SchedulerJobAuth;
import com.example.mybill.boutique.service.DropshipCleanupService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** POST /internal/jobs/dropship-cleanup[?dry_run=true] - Cloud Scheduler, every night (see DropshipCleanupService). */
@RestController
public class DropshipCleanupController {

    @Autowired private SchedulerJobAuth auth;
    @Autowired private DropshipCleanupService cleanup;

    @PostMapping("/internal/jobs/dropship-cleanup")
    public ResponseEntity<Map<String, Object>> run(@RequestParam(name = "dry_run", defaultValue = "false") boolean dryRun,
                                                   @RequestHeader(value = "Authorization", required = false) String authorization) {
        var rejected = auth.reject(authorization);
        if (rejected.isPresent()) return rejected.get();
        return ResponseEntity.ok(cleanup.run(dryRun));
    }
}

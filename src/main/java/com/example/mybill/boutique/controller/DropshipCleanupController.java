package com.example.mybill.boutique.controller;

import com.example.mybill.boutique.config.BoutiqueSchemaInitializer;
import com.example.mybill.boutique.config.SchedulerJobAuth;
import com.example.mybill.boutique.payment.TenantWork;
import com.example.mybill.boutique.service.DropshipBoostService;
import com.example.mybill.boutique.service.DropshipCleanupService;
import com.example.mybill.dto.Firm;
import com.example.mybill.repository.FirmRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Cloud Scheduler jobs for dropship: POST /internal/jobs/dropship-cleanup[?dry_run=true] every night (see
 * DropshipCleanupService) and POST /internal/jobs/boost-summary every evening (see DropshipBoostService).
 */
@RestController
public class DropshipCleanupController {

    private static final Logger log = LoggerFactory.getLogger(DropshipCleanupController.class);

    @Autowired private SchedulerJobAuth auth;
    @Autowired private DropshipCleanupService cleanup;
    @Autowired private DropshipBoostService boosts;
    @Autowired private FirmRepository firms;
    @Autowired private TenantWork tenantWork;
    @Autowired private BoutiqueSchemaInitializer schemaInitializer;

    @PostMapping("/internal/jobs/dropship-cleanup")
    public ResponseEntity<Map<String, Object>> run(@RequestParam(name = "dry_run", defaultValue = "false") boolean dryRun,
                                                   @RequestHeader(value = "Authorization", required = false) String authorization) {
        var rejected = auth.reject(authorization);
        if (rejected.isPresent()) return rejected.get();
        return ResponseEntity.ok(cleanup.run(dryRun));
    }

    @PostMapping("/internal/jobs/boost-summary")
    public ResponseEntity<Map<String, Object>> boostSummary(@RequestHeader(value = "Authorization", required = false) String authorization) {
        var rejected = auth.reject(authorization);
        if (rejected.isPresent()) return rejected.get();
        Map<String, Object> out = new LinkedHashMap<>();
        for (Firm firm : firms.findAll()) {
            String schema = firm.getSchemaName();
            if (schema == null || schema.isBlank()) continue;
            try {
                schemaInitializer.ensureSchema(schema);
                out.put(schema, tenantWork.in(schema, boosts::dailySummary));
            } catch (Exception e) {
                log.error("Boost summary failed for firm {}: {}", schema, e.toString());
                out.put(schema, "error");
            }
        }
        return ResponseEntity.ok(out);
    }
}

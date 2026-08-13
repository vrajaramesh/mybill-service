package com.example.mybill.controller;

import com.example.mybill.multitenancy.TenantContext;
import com.example.mybill.service.BusinessIntelligenceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/business-intelligence")
public class BusinessIntelligenceController {

    @Autowired
    private BusinessIntelligenceService biService;

    /** Start async report generation. Returns {reportId, status:"PENDING"} immediately. */
    @PostMapping("/report")
    public ResponseEntity<Map<String, Object>> startReport(
            @RequestBody(required = false) Map<String, Object> input) {
        return ResponseEntity.accepted().body(
            biService.startReport(input != null ? input : new HashMap<>())
        );
    }

    /** Poll report status/result by ID. */
    @GetMapping("/report/{id}")
    public ResponseEntity<Map<String, Object>> getReport(@PathVariable long id) {
        String schema = TenantContext.getCurrentTenant();
        if (schema == null) schema = "public";
        return ResponseEntity.ok(biService.getReport(id, schema));
    }

    /** Returns most recent report for this tenant (loads cached result on page visit). */
    @GetMapping("/report/latest")
    public ResponseEntity<Map<String, Object>> getLatestReport() {
        String schema = TenantContext.getCurrentTenant();
        if (schema == null) schema = "public";
        return ResponseEntity.ok(biService.getLatestReport(schema));
    }

    @GetMapping("/trends")
    public ResponseEntity<List<Map<String, Object>>> getTrends() {
        return ResponseEntity.ok(biService.getInstagramTrends());
    }
}

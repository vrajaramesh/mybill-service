package com.example.mybill.controller;

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

    @PostMapping("/report")
    public ResponseEntity<Map<String, Object>> generateReport(
            @RequestBody(required = false) Map<String, Object> input) {
        return ResponseEntity.ok(biService.generateReport(input != null ? input : new HashMap<>()));
    }

    @GetMapping("/trends")
    public ResponseEntity<List<Map<String, Object>>> getTrends() {
        return ResponseEntity.ok(biService.getInstagramTrends());
    }
}

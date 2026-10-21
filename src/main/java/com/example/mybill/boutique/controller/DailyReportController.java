package com.example.mybill.boutique.controller;

import com.example.mybill.boutique.exception.AgentApiException;
import com.example.mybill.boutique.service.AgentStaffClient;
import com.example.mybill.multitenancy.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;

/**
 * mybill-ui "Boutique → Daily report": the AI analysis of one day's WhatsApp/Instagram chats and sales, made by
 * srisa-agent every evening (or on demand). Normal app login; only the agent's firm has reports.
 */
@RestController
@RequestMapping("/api/boutique-reports")
public class DailyReportController {

    @Autowired private AgentStaffClient agent;
    @Autowired private ObjectMapper objectMapper;
    @Value("${agent.tenant-schema:firm_srisa}") private String agentTenant;

    @GetMapping
    public JsonNode list() {
        if (!agentTenant.equals(TenantContext.getCurrentTenant())) return objectMapper.createArrayNode();
        return agent.get("/staff/reports?limit=60");
    }

    @GetMapping("/{date}")
    public JsonNode get(@PathVariable String date) {
        requireAgentFirm();
        return agent.get("/staff/reports/" + day(date));
    }

    /** (Re)generate the report for a day now - takes 1-3 minutes. */
    @PostMapping("/{date}/generate")
    public JsonNode generate(@PathVariable String date) {
        requireAgentFirm();
        return agent.post("/staff/reports/" + day(date) + "/generate", Map.of(), Duration.ofSeconds(290));
    }

    private void requireAgentFirm() {
        if (!agentTenant.equals(TenantContext.getCurrentTenant())) {
            throw AgentApiException.notFound("REPORT_NOT_FOUND", "No daily reports for this firm");
        }
    }

    private static String day(String date) {
        try {
            return LocalDate.parse(date).toString();
        } catch (Exception e) {
            throw AgentApiException.notFound("REPORT_NOT_FOUND", "Not a date: " + date);
        }
    }
}

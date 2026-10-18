package com.example.mybill.boutique.controller;

import com.example.mybill.boutique.config.BoutiqueSchemaInitializer;
import com.example.mybill.boutique.dto.*;
import com.example.mybill.boutique.exception.AgentApiException;
import com.example.mybill.boutique.service.MarketingService;
import com.example.mybill.multitenancy.TenantContext;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * mybill-ui "Ad & post links" and "Ad performance" pages. Normal app login (TenantFilter JWT), so each firm manages
 * its own links; the agent only reads them through /api/agent/marketing-links/resolve.
 */
@RestController
@RequestMapping("/api/boutique-marketing")
public class MarketingAdminController {

    @Autowired private MarketingService marketingService;
    @Autowired private BoutiqueSchemaInitializer schemaInitializer;

    @GetMapping("/links")
    public List<MarketingLinkResponse> links() {
        requireFirm();
        return marketingService.listLinks();
    }

    @PostMapping("/links")
    public ResponseEntity<MarketingLinkResponse> create(@Valid @RequestBody MarketingLinkRequest request) {
        requireFirm();
        return ResponseEntity.status(HttpStatus.CREATED).body(marketingService.createLink(request));
    }

    @PutMapping("/links/{linkId}")
    public MarketingLinkResponse update(@PathVariable int linkId, @Valid @RequestBody MarketingLinkRequest request) {
        requireFirm();
        return marketingService.updateLink(linkId, request);
    }

    @PostMapping("/links/{linkId}/deactivate")
    public MarketingLinkResponse deactivate(@PathVariable int linkId) {
        requireFirm();
        return marketingService.setActive(linkId, false);
    }

    @PostMapping("/links/{linkId}/activate")
    public MarketingLinkResponse activate(@PathVariable int linkId) {
        requireFirm();
        return marketingService.setActive(linkId, true);
    }

    /** Designs with a code, for the design picker. */
    @GetMapping("/designs")
    public List<DesignOptionResponse> designs() {
        requireFirm();
        return marketingService.designOptions();
    }

    /** e.g. ?from=2026-10-01&to=2026-10-18 (IST days, inclusive; default = last 30 days). */
    @GetMapping("/report")
    public MarketingReport report(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        requireFirm();
        return marketingService.report(from, to);
    }

    private void requireFirm() {
        String schema = TenantContext.getCurrentTenant();
        if (schema == null || schema.isBlank() || "public".equals(schema)) {
            throw new AgentApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Marketing links require a firm login", null);
        }
        schemaInitializer.ensureSchema(schema);
    }
}

package com.example.mybill.wholesale.controller;

import com.example.mybill.wholesale.config.WholesaleAccess;
import com.example.mybill.wholesale.dto.*;
import com.example.mybill.wholesale.service.WholesaleInventoryReportService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/** Wholesale Stock: current stock, ledger, product history, movements, adjustments, settings. See docs/wholesale-inventory-api.md. */
@RestController
@RequestMapping("/api/wholesale/inventory")
public class WholesaleInventoryController {

    @Autowired private WholesaleInventoryReportService reportService;
    @Autowired private WholesaleAccess access;

    @GetMapping("/stock")
    public List<WholesaleCurrentStockRow> stock(@RequestParam(required = false) String q,
                                                @RequestParam(defaultValue = "false") boolean includeInactive,
                                                @RequestParam(defaultValue = "false") boolean onlyInStock) {
        return reportService.currentStock(q, includeInactive, onlyInStock);
    }

    /** ?productId=&from=&to=&type=SALE,PURCHASE&referenceType=PURCHASE&q=<ref no>&limit= (newest first) */
    @GetMapping("/ledger")
    public List<WholesaleLedgerEntryResponse> ledger(@RequestParam(required = false) Integer productId,
                                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                     @RequestParam(required = false) String type,
                                                     @RequestParam(required = false) String referenceType,
                                                     @RequestParam(required = false) String q,
                                                     @RequestParam(required = false) Integer limit) {
        return reportService.ledger(productId, from, to, type, referenceType, q, limit);
    }

    @GetMapping("/products/{productId}/history")
    public WholesaleProductStockHistory history(@PathVariable Integer productId,
                                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reportService.productHistory(productId, from, to);
    }

    @GetMapping("/movements")
    public List<WholesaleStockMovementRow> movements(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                     @RequestParam(required = false) String q,
                                                     @RequestParam(defaultValue = "true") boolean onlyMoved) {
        return reportService.movements(from, to, q, onlyMoved);
    }

    @GetMapping("/adjustments")
    public List<WholesaleStockAdjustmentResponse> adjustments() {
        return reportService.adjustments();
    }

    @GetMapping("/adjustments/{id}")
    public WholesaleStockAdjustmentResponse adjustment(@PathVariable Integer id) {
        return reportService.adjustment(id);
    }

    /** ADMIN only. */
    @PostMapping("/adjustments")
    public ResponseEntity<WholesaleStockAdjustmentResponse> createAdjustment(@Valid @RequestBody WholesaleStockAdjustmentRequest request,
                                                                             HttpServletRequest http) {
        String user = access.requireAdmin(http);
        return ResponseEntity.status(HttpStatus.CREATED).body(reportService.createAdjustment(request, user));
    }

    @GetMapping("/settings")
    public WholesaleInventorySettingsDto settings() {
        return reportService.settings();
    }

    /** ADMIN only. */
    @PutMapping("/settings")
    public WholesaleInventorySettingsDto updateSettings(@Valid @RequestBody WholesaleInventorySettingsDto request, HttpServletRequest http) {
        String user = access.requireAdmin(http);
        return reportService.updateSettings(request.allowNegativeStock(), user);
    }
}

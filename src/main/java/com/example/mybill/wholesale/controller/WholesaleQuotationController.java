package com.example.mybill.wholesale.controller;

import com.example.mybill.wholesale.config.WholesaleAccess;
import com.example.mybill.wholesale.dto.*;
import com.example.mybill.wholesale.service.WholesaleQuotationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/** Wholesale quotations. See mybill-service/docs/wholesale-quotations-api.md. */
@RestController
@RequestMapping("/api/wholesale/quotations")
public class WholesaleQuotationController {

    @Autowired private WholesaleQuotationService quotationService;
    @Autowired private WholesaleAccess access;

    /** ?q=&status=ISSUED,ACCEPTED&from=2026-04-01&to=2027-03-31&limit=100 */
    @GetMapping
    public List<WholesaleQuotationSummary> list(@RequestParam(required = false) String q,
                                                @RequestParam(required = false) String status,
                                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                @RequestParam(required = false) Integer limit) {
        return quotationService.list(q, status, from, to, limit);
    }

    @GetMapping("/{id}")
    public WholesaleQuotationResponse get(@PathVariable Integer id) {
        return quotationService.get(id);
    }

    /** Calculates without saving (live totals in the editor). */
    @PostMapping("/preview")
    public WholesaleQuotationResponse preview(@Valid @RequestBody WholesaleQuotationRequest request) {
        return quotationService.preview(request);
    }

    @PostMapping
    public ResponseEntity<WholesaleQuotationResponse> create(@Valid @RequestBody WholesaleQuotationRequest request,
                                                             HttpServletRequest http) {
        return ResponseEntity.status(HttpStatus.CREATED).body(quotationService.create(request, access.username(http)));
    }

    @PutMapping("/{id}")
    public WholesaleQuotationResponse update(@PathVariable Integer id, @Valid @RequestBody WholesaleQuotationRequest request) {
        return quotationService.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Integer id) {
        quotationService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/duplicate")
    public ResponseEntity<WholesaleQuotationResponse> duplicate(@PathVariable Integer id,
                                                                @RequestParam(defaultValue = "true") boolean reprice,
                                                                HttpServletRequest http) {
        return ResponseEntity.status(HttpStatus.CREATED).body(quotationService.duplicate(id, reprice, access.username(http)));
    }

    @PostMapping("/{id}/issue")
    public WholesaleQuotationResponse issue(@PathVariable Integer id, HttpServletRequest http) {
        return quotationService.issue(id, access.username(http));
    }

    @PostMapping("/{id}/status")
    public WholesaleQuotationResponse changeStatus(@PathVariable Integer id,
                                                   @Valid @RequestBody WholesaleQuotationStatusRequest request,
                                                   HttpServletRequest http) {
        return quotationService.changeStatus(id, request, access.username(http));
    }
}

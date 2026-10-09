package com.example.mybill.wholesale.controller;

import com.example.mybill.wholesale.config.WholesaleAccess;
import com.example.mybill.wholesale.dto.*;
import com.example.mybill.wholesale.service.WholesaleSalesDocumentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/** Quotation conversion + wholesale Sales Receipts / Credit Notes. See docs/wholesale-sales-receipts-api.md. */
@RestController
@RequestMapping("/api/wholesale")
public class WholesaleSalesDocumentController {

    @Autowired private WholesaleSalesDocumentService documentService;
    @Autowired private WholesaleAccess access;

    @PostMapping("/quotations/{quotationId}/convert")
    public ResponseEntity<WholesaleSalesDocumentResponse> convert(@PathVariable Integer quotationId,
                                                                  @Valid @RequestBody WholesaleConversionRequest request,
                                                                  HttpServletRequest http) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(documentService.convert(quotationId, request, access.username(http)));
    }

    /** ?type=SALES_RECEIPT,CREDIT_NOTE,DEBIT_NOTE&status=ISSUED,PARTIALLY_PAID&q=&from=&to=&limit= */
    @GetMapping("/sales-documents")
    public List<WholesaleSalesDocumentSummary> list(@RequestParam(required = false) String type,
                                                    @RequestParam(required = false) String status,
                                                    @RequestParam(required = false) String q,
                                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                    @RequestParam(required = false) Integer limit) {
        return documentService.list(type, status, q, from, to, limit);
    }

    @GetMapping("/sales-documents/{id}")
    public WholesaleSalesDocumentResponse get(@PathVariable Integer id) {
        return documentService.get(id);
    }

    // ── Direct Sales Receipts (drafts) ───────────────────────

    /** Calculates a receipt exactly as save would, without saving (live totals in the editor). */
    @PostMapping("/sales-receipts/preview")
    public WholesaleSalesDocumentResponse previewReceipt(@Valid @RequestBody WholesaleSalesReceiptRequest request) {
        return documentService.previewReceipt(request);
    }

    @PostMapping("/sales-receipts")
    public ResponseEntity<WholesaleSalesDocumentResponse> createReceipt(@Valid @RequestBody WholesaleSalesReceiptRequest request,
                                                                        HttpServletRequest http) {
        return ResponseEntity.status(HttpStatus.CREATED).body(documentService.createReceipt(request, access.username(http)));
    }

    @PutMapping("/sales-receipts/{id}")
    public WholesaleSalesDocumentResponse updateReceipt(@PathVariable Integer id, @Valid @RequestBody WholesaleSalesReceiptRequest request,
                                                        HttpServletRequest http) {
        return documentService.updateReceipt(id, request, access.username(http));
    }

    // ── Any document type (Sales Receipt / Credit Note / Debit Note) ──

    @PostMapping("/sales-documents/preview")
    public WholesaleSalesDocumentResponse preview(@Valid @RequestBody WholesaleSalesDocumentRequest request) {
        return documentService.preview(request);
    }

    @PostMapping("/sales-documents")
    public ResponseEntity<WholesaleSalesDocumentResponse> create(@Valid @RequestBody WholesaleSalesDocumentRequest request,
                                                                 HttpServletRequest http) {
        return ResponseEntity.status(HttpStatus.CREATED).body(documentService.create(request, access.username(http)));
    }

    @PutMapping("/sales-documents/{id}")
    public WholesaleSalesDocumentResponse update(@PathVariable Integer id, @Valid @RequestBody WholesaleSalesDocumentRequest request,
                                                 HttpServletRequest http) {
        return documentService.update(id, request, access.username(http));
    }

    @DeleteMapping("/sales-documents/{id}")
    public ResponseEntity<Void> deleteDraft(@PathVariable Integer id) {
        documentService.deleteDraft(id);
        return ResponseEntity.noContent().build();
    }

    /** DRAFT → ISSUED; body may include the payment received now: {"payment": {...}}. */
    @PostMapping("/sales-documents/{id}/issue")
    public WholesaleSalesDocumentResponse issue(@PathVariable Integer id,
                                                @Valid @RequestBody(required = false) WholesaleIssueRequest request,
                                                HttpServletRequest http) {
        return documentService.issue(id, request, access.username(http));
    }

    // ── Payments ─────────────────────────────────────────────

    @PostMapping("/sales-documents/{id}/payments")
    public WholesaleSalesDocumentResponse addPayment(@PathVariable Integer id, @Valid @RequestBody WholesalePaymentRequest request,
                                                     HttpServletRequest http) {
        return documentService.addPayment(id, request, access.username(http));
    }

    @PostMapping("/sales-documents/{id}/payments/{paymentId}/void")
    public WholesaleSalesDocumentResponse voidPayment(@PathVariable Integer id, @PathVariable Integer paymentId,
                                                      @Valid @RequestBody WholesaleCancelRequest request, HttpServletRequest http) {
        return documentService.voidPayment(id, paymentId, request.reason(), access.username(http));
    }

    @PostMapping("/sales-documents/{id}/cancel")
    public WholesaleSalesDocumentResponse cancel(@PathVariable Integer id, @Valid @RequestBody WholesaleCancelRequest request,
                                                 HttpServletRequest http) {
        return documentService.cancel(id, request.reason(), access.username(http));
    }
}

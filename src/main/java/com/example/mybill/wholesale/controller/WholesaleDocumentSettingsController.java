package com.example.mybill.wholesale.controller;

import com.example.mybill.wholesale.config.WholesaleAccess;
import com.example.mybill.wholesale.dto.WholesaleDocumentSettingsRequest;
import com.example.mybill.wholesale.dto.WholesaleDocumentSettingsResponse;
import com.example.mybill.wholesale.exception.WholesaleException;
import com.example.mybill.wholesale.service.WholesaleDocumentNumberService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Settings → Document Types & Numbering (numbering + configurable semantics). Reading is open; changes are ADMIN-only. */
@RestController
@RequestMapping("/api/wholesale/document-settings")
public class WholesaleDocumentSettingsController {

    private static final Set<String> DOC_TYPES = Set.of("QUOTATION", "SALES_RECEIPT", "CREDIT_NOTE", "DEBIT_NOTE", "STOCK_ADJUSTMENT");

    @Autowired private WholesaleDocumentNumberService numberService;
    @Autowired private WholesaleAccess access;

    /** All document types with their labels and configured semantics. */
    @GetMapping
    public List<WholesaleDocumentSettingsResponse> list() {
        return numberService.listSettings();
    }

    @GetMapping("/{docType}")
    public WholesaleDocumentSettingsResponse get(@PathVariable String docType) {
        return numberService.getSettings(docType(docType));
    }

    @PutMapping("/{docType}")
    public WholesaleDocumentSettingsResponse update(@PathVariable String docType,
                                                    @Valid @RequestBody WholesaleDocumentSettingsRequest request,
                                                    HttpServletRequest http) {
        String user = access.requireAdmin(http);
        return numberService.updateSettings(docType(docType), request, user);
    }

    private static String docType(String raw) {
        String t = raw.toUpperCase(Locale.ROOT);
        if (!DOC_TYPES.contains(t)) throw WholesaleException.badRequest("Unknown document type " + raw);
        return t;
    }
}

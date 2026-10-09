package com.example.mybill.wholesale.controller;

import com.example.mybill.wholesale.config.WholesaleAccess;
import com.example.mybill.wholesale.dto.*;
import com.example.mybill.wholesale.service.WholesaleBusinessProfileService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * Settings → Wholesale Business Profile / Bank Details / UPI Details.
 * Any firm user can read (documents need it); only a firm ADMIN can change it.
 */
@RestController
@RequestMapping("/api/wholesale/business-profile")
public class WholesaleBusinessProfileController {

    @Autowired private WholesaleBusinessProfileService profileService;
    @Autowired private WholesaleAccess access;

    @GetMapping
    public WholesaleBusinessProfileResponse get() {
        return profileService.get();
    }

    /** Print-ready profile for quotations, credit invoices, sales receipts and debit notes (409 if incomplete). */
    @GetMapping("/document")
    public WholesaleDocumentProfile document() {
        return profileService.documentProfile();
    }

    @PutMapping("/details")
    public WholesaleBusinessProfileResponse saveDetails(@Valid @RequestBody WholesaleProfileDetailsRequest request,
                                                        HttpServletRequest http) {
        return profileService.saveDetails(request, access.requireAdmin(http));
    }

    @PutMapping("/bank")
    public WholesaleBusinessProfileResponse saveBank(@Valid @RequestBody WholesaleBankDetailsRequest request,
                                                     HttpServletRequest http) {
        return profileService.saveBank(request, access.requireAdmin(http));
    }

    @DeleteMapping("/bank")
    public WholesaleBusinessProfileResponse clearBank(HttpServletRequest http) {
        return profileService.clearBank(access.requireAdmin(http));
    }

    @PutMapping("/upi")
    public WholesaleBusinessProfileResponse saveUpi(@Valid @RequestBody WholesaleUpiDetailsRequest request,
                                                    HttpServletRequest http) {
        return profileService.saveUpi(request, access.requireAdmin(http));
    }

    @DeleteMapping("/upi")
    public WholesaleBusinessProfileResponse clearUpi(HttpServletRequest http) {
        return profileService.clearUpi(access.requireAdmin(http));
    }
}

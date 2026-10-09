package com.example.mybill.wholesale.service;

import com.example.mybill.wholesale.dto.*;
import com.example.mybill.wholesale.entity.WholesaleBusinessProfile;
import com.example.mybill.wholesale.exception.WholesaleException;
import com.example.mybill.wholesale.repository.WholesaleBusinessProfileRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Wholesale business profile (letterhead, bank, UPI). One row per firm schema.
 * Retail store details (browser-side SettingsService) are not read or changed here.
 */
@Service
public class WholesaleBusinessProfileService {

    @Autowired private WholesaleBusinessProfileRepository profileRepository;

    @Transactional(readOnly = true)
    public WholesaleBusinessProfileResponse get() {
        return toResponse(profileRepository.findById(WholesaleBusinessProfile.SINGLETON_ID).orElse(null));
    }

    @Transactional
    public WholesaleBusinessProfileResponse saveDetails(WholesaleProfileDetailsRequest r, String username) {
        String stateCode = r.stateCode().trim();
        if (!GstStates.isValid(stateCode)) throw WholesaleException.badRequest("Unknown state code " + stateCode);
        String gst = WholesaleCustomerService.normalizeGst(r.gstNumber());
        if (gst != null && !gst.startsWith(stateCode)) {
            String gstState = gst.substring(0, 2);
            throw WholesaleException.badRequest("GST number is registered in "
                + (GstStates.isValid(gstState) ? GstStates.name(gstState) : "state code " + gstState) + " (" + gstState
                + ") but the firm state is " + GstStates.name(stateCode) + " (" + stateCode + ")");
        }

        WholesaleBusinessProfile p = loadOrCreate();
        p.setFirmName(r.firmName().trim());
        p.setAddress(r.address().trim());
        p.setCity(WholesaleProductService.blankToNull(r.city()));
        p.setStateCode(stateCode);
        p.setStateName(GstStates.name(stateCode));
        p.setPinCode(WholesaleProductService.blankToNull(r.pinCode()));
        p.setGstNumber(gst);
        p.setPhone(WholesaleCustomerService.normalizePhone(r.phone()));
        String email = WholesaleProductService.blankToNull(r.email());
        p.setEmail(email == null ? null : email.toLowerCase(Locale.ROOT));
        p.setWebsite(normalizeWebsite(r.website()));
        p.setLogoUrl(WholesaleProductService.blankToNull(r.logoUrl()));
        p.setLogoPublicId(p.getLogoUrl() == null ? null : WholesaleProductService.blankToNull(r.logoPublicId()));
        p.setUpdatedBy(username);
        return toResponse(profileRepository.save(p));
    }

    @Transactional
    public WholesaleBusinessProfileResponse saveBank(WholesaleBankDetailsRequest r, String username) {
        WholesaleBusinessProfile p = loadOrCreate();
        p.setBankName(r.bankName().trim());
        p.setBankAccountName(r.accountName().trim());
        p.setBankAccountNumber(r.accountNumber().trim());
        p.setBankIfsc(r.ifsc().trim().toUpperCase(Locale.ROOT));
        p.setBankBranch(WholesaleProductService.blankToNull(r.branch()));
        p.setUpdatedBy(username);
        return toResponse(profileRepository.save(p));
    }

    @Transactional
    public WholesaleBusinessProfileResponse clearBank(String username) {
        WholesaleBusinessProfile p = loadOrCreate();
        p.setBankName(null);
        p.setBankAccountName(null);
        p.setBankAccountNumber(null);
        p.setBankIfsc(null);
        p.setBankBranch(null);
        p.setUpdatedBy(username);
        return toResponse(profileRepository.save(p));
    }

    @Transactional
    public WholesaleBusinessProfileResponse saveUpi(WholesaleUpiDetailsRequest r, String username) {
        WholesaleBusinessProfile p = loadOrCreate();
        p.setUpiId(r.upiId().trim().toLowerCase(Locale.ROOT));
        p.setUpiQrUrl(WholesaleProductService.blankToNull(r.qrImageUrl()));
        p.setUpiQrPublicId(p.getUpiQrUrl() == null ? null : WholesaleProductService.blankToNull(r.qrPublicId()));
        p.setUpdatedBy(username);
        return toResponse(profileRepository.save(p));
    }

    @Transactional
    public WholesaleBusinessProfileResponse clearUpi(String username) {
        WholesaleBusinessProfile p = loadOrCreate();
        p.setUpiId(null);
        p.setUpiQrUrl(null);
        p.setUpiQrPublicId(null);
        p.setUpdatedBy(username);
        return toResponse(profileRepository.save(p));
    }

    /**
     * Print-ready profile for wholesale documents. 409 until the mandatory profile fields are filled,
     * so no document is generated without the firm's name, address, state and phone.
     */
    @Transactional(readOnly = true)
    public WholesaleDocumentProfile documentProfile() {
        WholesaleBusinessProfile p = profileRepository.findById(WholesaleBusinessProfile.SINGLETON_ID).orElse(null);
        List<String> missing = missing(p);
        if (!missing.isEmpty()) {
            throw WholesaleException.conflict("Complete Settings → Wholesale Business Profile before generating documents. Missing: "
                + String.join(", ", missing));
        }
        List<String> lines = new ArrayList<>();
        p.getAddress().lines().map(String::trim).filter(l -> !l.isEmpty()).forEach(lines::add);
        String place = String.join(", ", nonNull(p.getCity(), p.getStateName())) + (p.getPinCode() != null ? " - " + p.getPinCode() : "");
        if (!place.isBlank()) lines.add(place.trim());

        WholesaleDocumentProfile.Bank bank = bankConfigured(p) ? new WholesaleDocumentProfile.Bank(
            p.getBankName(), p.getBankAccountName(), p.getBankAccountNumber(), p.getBankIfsc(), p.getBankBranch()) : null;
        WholesaleDocumentProfile.Upi upi = p.getUpiId() != null
            ? new WholesaleDocumentProfile.Upi(p.getUpiId(), p.getFirmName(), p.getUpiQrUrl()) : null;

        return new WholesaleDocumentProfile(p.getFirmName(), lines, p.getStateCode(), p.getStateName(), p.getGstNumber(),
            p.getPhone(), p.getEmail(), p.getWebsite(), p.getLogoUrl(), bank, upi);
    }

    private WholesaleBusinessProfile loadOrCreate() {
        return profileRepository.findById(WholesaleBusinessProfile.SINGLETON_ID).orElseGet(WholesaleBusinessProfile::new);
    }

    private static List<String> missing(WholesaleBusinessProfile p) {
        List<String> missing = new ArrayList<>();
        if (p == null || p.getFirmName() == null) missing.add("firm name");
        if (p == null || p.getAddress() == null) missing.add("firm address");
        if (p == null || p.getStateCode() == null) missing.add("state");
        if (p == null || p.getPhone() == null) missing.add("phone number");
        return missing;
    }

    private static boolean bankConfigured(WholesaleBusinessProfile p) {
        return p != null && p.getBankName() != null && p.getBankAccountNumber() != null && p.getBankIfsc() != null;
    }

    private static List<String> nonNull(String... values) {
        List<String> out = new ArrayList<>();
        for (String v : values) if (v != null && !v.isBlank()) out.add(v);
        return out;
    }

    /** "www.example.com" -> "https://www.example.com"; keeps an explicit http(s) scheme. */
    static String normalizeWebsite(String website) {
        String v = WholesaleProductService.blankToNull(website);
        if (v == null) return null;
        return v.matches("(?i)^https?://.*") ? v : "https://" + v;
    }

    private static WholesaleBusinessProfileResponse toResponse(WholesaleBusinessProfile p) {
        List<String> missing = missing(p);
        boolean gst = p != null && p.getGstNumber() != null;
        boolean bank = bankConfigured(p);
        boolean upi = p != null && p.getUpiId() != null;
        List<String> warnings = new ArrayList<>();
        if (!gst) warnings.add("No GST number: documents will be issued as an unregistered business (no tax invoice).");
        if (!bank) warnings.add("No bank details: documents will not show bank transfer details.");
        if (!upi) warnings.add("No UPI ID: documents will not show UPI payment details.");
        WholesaleBusinessProfileResponse.Readiness readiness =
            new WholesaleBusinessProfileResponse.Readiness(missing.isEmpty(), gst, bank, upi, missing, warnings);
        if (p == null) return new WholesaleBusinessProfileResponse(null, null, null, readiness, null, null);

        return new WholesaleBusinessProfileResponse(
            new WholesaleBusinessProfileResponse.Details(p.getFirmName(), p.getAddress(), p.getCity(), p.getStateCode(),
                p.getStateName(), p.getPinCode(), p.getGstNumber(), p.getPhone(), p.getEmail(), p.getWebsite(),
                p.getLogoUrl(), p.getLogoPublicId()),
            new WholesaleBusinessProfileResponse.Bank(p.getBankName(), p.getBankAccountName(), p.getBankAccountNumber(),
                p.getBankIfsc(), p.getBankBranch()),
            new WholesaleBusinessProfileResponse.Upi(p.getUpiId(), p.getUpiQrUrl(), p.getUpiQrPublicId()),
            readiness, p.getUpdatedAt(), p.getUpdatedBy());
    }
}

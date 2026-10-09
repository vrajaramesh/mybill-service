package com.example.mybill.wholesale.service;

import com.example.mybill.wholesale.dto.WholesaleDocumentSettingsRequest;
import com.example.mybill.wholesale.dto.WholesaleDocumentSettingsResponse;
import com.example.mybill.wholesale.entity.*;
import com.example.mybill.wholesale.exception.WholesaleException;
import com.example.mybill.wholesale.repository.WholesaleDocumentSettingsRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Predicate;

/**
 * Configurable, concurrency-safe document numbers. The counter row is incremented with a single
 * INSERT … ON CONFLICT … RETURNING statement (atomic per firm schema), inside the caller's transaction,
 * so a rolled-back issue does not consume the number permanently.
 */
@Service
public class WholesaleDocumentNumberService {

    @PersistenceContext private EntityManager entityManager;
    @Autowired private WholesaleDocumentSettingsRepository settingsRepository;

    /**
     * Next unused number for docType, dated documentDate. {@code taken} reports numbers already in use
     * (e.g. after the format was changed), which are skipped.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String next(String docType, LocalDate documentDate, Predicate<String> taken) {
        WholesaleDocumentSettings s = settings(docType);
        String period = WholesaleDocumentNumbering.period(s.getResetEachFinancialYear(), documentDate);
        for (int attempt = 0; attempt < 1000; attempt++) {
            int sequence = increment(docType, period);
            String number = WholesaleDocumentNumbering.format(s.getPrefix(), s.getSeparator(),
                s.getIncludeFinancialYear(), s.getNumberPadding(), documentDate, sequence);
            if (!taken.test(number)) return number;
        }
        throw WholesaleException.conflict("Could not find a free " + docType.toLowerCase() + " number; check the numbering settings");
    }

    @Transactional(readOnly = true)
    public WholesaleDocumentSettingsResponse getSettings(String docType) {
        return toResponse(settings(docType));
    }

    @Transactional(readOnly = true)
    public WholesaleDocumentSettings settings(String docType) {
        return settingsRepository.findById(docType)
            .orElseThrow(() -> WholesaleException.notFound("No numbering settings for " + docType));
    }

    @Transactional
    public WholesaleDocumentSettingsResponse updateSettings(String docType, WholesaleDocumentSettingsRequest r, String username) {
        WholesaleDocumentSettings s = settings(docType);
        s.setPrefix(r.prefix().trim());
        s.setSeparator(r.separator());
        s.setIncludeFinancialYear(r.includeFinancialYear());
        s.setResetEachFinancialYear(r.resetEachFinancialYear());
        s.setNumberPadding(r.numberPadding());
        s.setDefaultValidityDays(r.defaultValidityDays());
        s.setDefaultTerms(WholesaleProductService.blankToNull(r.defaultTerms()));
        if (r.defaultDueDays() != null) s.setDefaultDueDays(r.defaultDueDays());
        applySemantics(s, r);
        s.setUpdatedBy(username);
        settingsRepository.saveAndFlush(s);

        if (r.nextNumber() != null) {
            String period = WholesaleDocumentNumbering.period(s.getResetEachFinancialYear(), today());
            int last = lastNumber(docType, period);
            if (r.nextNumber() <= last) {
                throw WholesaleException.badRequest("Next number must be greater than " + last
                    + " (the last number already used in " + period + ")");
            }
            entityManager.createNativeQuery("""
                    INSERT INTO wholesale_document_counters (doc_type, period, last_number) VALUES (:t, :p, :v)
                    ON CONFLICT (doc_type, period) DO UPDATE SET last_number = EXCLUDED.last_number""")
                .setParameter("t", docType).setParameter("p", period).setParameter("v", r.nextNumber() - 1)
                .executeUpdate();
        }
        return toResponse(s);
    }

    /** All document types (labels and semantics), e.g. for menus and tabs. */
    @Transactional(readOnly = true)
    public List<WholesaleDocumentSettingsResponse> listSettings() {
        return settingsRepository.findAll().stream()
            .sorted(Comparator.comparing(WholesaleDocumentSettings::getDocType))
            .map(this::toResponse).toList();
    }

    /**
     * Semantics are configuration, not code. Changes affect documents created/issued afterwards only, because every
     * document stores its own copy at issue time.
     */
    private static void applySemantics(WholesaleDocumentSettings s, WholesaleDocumentSettingsRequest r) {
        if (WholesaleDocumentSettings.QUOTATION.equals(s.getDocType())
            || WholesaleDocumentSettings.STOCK_ADJUSTMENT.equals(s.getDocType())) return;
        if (r.displayLabel() != null && !r.displayLabel().isBlank()) s.setDisplayLabel(r.displayLabel().trim());
        if (r.printTitle() != null && !r.printTitle().isBlank()) s.setPrintTitle(r.printTitle().trim());
        if (r.gstClassification() != null && !r.gstClassification().isBlank())
            s.setGstClassification(WholesaleGstClassification.valueOf(r.gstClassification()));
        if (r.valueEffect() != null && !r.valueEffect().isBlank()) s.setValueEffect(WholesaleValueEffect.valueOf(r.valueEffect()));
        if (r.stockEffect() != null && !r.stockEffect().isBlank()) s.setStockEffect(WholesaleStockEffect.valueOf(r.stockEffect()));
        if (r.referenceMode() != null && !r.referenceMode().isBlank()) s.setReferenceMode(WholesaleReferenceMode.valueOf(r.referenceMode()));
        if (r.allowedReferenceTypes() != null) s.setAllowedReferenceTypes(String.join(",", new LinkedHashSet<>(r.allowedReferenceTypes())));
        if (r.reasonRequired() != null) s.setReasonRequired(r.reasonRequired());
        if (s.getValueEffect() == WholesaleValueEffect.CREDIT && s.getStockEffect() == WholesaleStockEffect.OUT) {
            throw WholesaleException.badRequest("A credit document (customer owes less) cannot take goods out of stock; use IN or NONE");
        }
    }

    private int increment(String docType, String period) {
        Object result = entityManager.createNativeQuery("""
                INSERT INTO wholesale_document_counters (doc_type, period, last_number) VALUES (:t, :p, 1)
                ON CONFLICT (doc_type, period)
                DO UPDATE SET last_number = wholesale_document_counters.last_number + 1
                RETURNING last_number""")
            .setParameter("t", docType).setParameter("p", period)
            .getSingleResult();
        return ((Number) result).intValue();
    }

    private int lastNumber(String docType, String period) {
        List<?> rows = entityManager.createNativeQuery(
                "SELECT last_number FROM wholesale_document_counters WHERE doc_type = :t AND period = :p")
            .setParameter("t", docType).setParameter("p", period)
            .getResultList();
        return rows.isEmpty() ? 0 : ((Number) rows.get(0)).intValue();
    }

    static LocalDate today() {
        return LocalDate.now(WholesalePricingService.BUSINESS_ZONE);
    }

    private WholesaleDocumentSettingsResponse toResponse(WholesaleDocumentSettings s) {
        LocalDate today = today();
        String period = WholesaleDocumentNumbering.period(s.getResetEachFinancialYear(), today);
        int last = lastNumber(s.getDocType(), period);
        String preview = WholesaleDocumentNumbering.format(s.getPrefix(), s.getSeparator(), s.getIncludeFinancialYear(),
            s.getNumberPadding(), today, last + 1L);
        return new WholesaleDocumentSettingsResponse(s.getDocType(), s.getPrefix(), s.getSeparator(),
            s.getIncludeFinancialYear(), s.getResetEachFinancialYear(), s.getNumberPadding(), s.getDefaultValidityDays(),
            s.getDefaultTerms(), s.getDefaultDueDays(), period, last, preview, s.getUpdatedAt(), s.getUpdatedBy(),
            s.getDisplayLabel(), s.getPrintTitle(), s.getGstClassification().name(), s.getValueEffect().name(),
            s.getStockEffect().name(), s.getReferenceMode().name(), List.copyOf(s.allowedReferenceTypeSet()), s.getReasonRequired());
    }
}

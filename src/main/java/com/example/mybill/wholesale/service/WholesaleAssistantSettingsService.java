package com.example.mybill.wholesale.service;

import com.example.mybill.wholesale.dto.WholesaleAssistantSettingsDto;
import com.example.mybill.wholesale.entity.WholesaleAssistantSettings;
import com.example.mybill.wholesale.repository.WholesaleAssistantSettingsRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Wholesale AI assistant settings and business knowledge base (single row, created by V12). */
@Service
public class WholesaleAssistantSettingsService {

    @Autowired private WholesaleAssistantSettingsRepository repository;
    @Autowired private WholesaleLlmClient llm;

    @Transactional(readOnly = true)
    public WholesaleAssistantSettings current() {
        return repository.findById(WholesaleAssistantSettings.SINGLETON_ID).orElseGet(WholesaleAssistantSettings::new);
    }

    @Transactional(readOnly = true)
    public WholesaleAssistantSettingsDto get() {
        return toDto(current());
    }

    @Transactional
    public WholesaleAssistantSettingsDto save(WholesaleAssistantSettingsDto r, String username) {
        WholesaleAssistantSettings s = repository.findById(WholesaleAssistantSettings.SINGLETON_ID).orElseGet(WholesaleAssistantSettings::new);
        s.setOrderingProcess(blankToNull(r.orderingProcess()));
        s.setShippingInfo(blankToNull(r.shippingInfo()));
        s.setPaymentTerms(blankToNull(r.paymentTerms()));
        s.setBusinessHours(blankToNull(r.businessHours()));
        s.setAdditionalInfo(blankToNull(r.additionalInfo()));
        s.setUnknownReply(r.unknownReply().trim());
        if (r.shareStockQuantity() != null) s.setShareStockQuantity(r.shareStockQuantity());
        if (r.allowQuotationDrafts() != null) s.setAllowQuotationDrafts(r.allowQuotationDrafts());
        if (r.autoIssueQuotations() != null) s.setAutoIssueQuotations(r.autoIssueQuotations());
        s.setUpdatedBy(username);
        return toDto(repository.save(s));
    }

    private WholesaleAssistantSettingsDto toDto(WholesaleAssistantSettings s) {
        return new WholesaleAssistantSettingsDto(s.getOrderingProcess(), s.getShippingInfo(), s.getPaymentTerms(),
            s.getBusinessHours(), s.getAdditionalInfo(), s.getUnknownReply(), s.getShareStockQuantity(),
            s.getAllowQuotationDrafts(), s.getAutoIssueQuotations(), llm.isConfigured(), llm.model(),
            s.getUpdatedAt(), s.getUpdatedBy());
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}

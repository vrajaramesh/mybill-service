package com.example.mybill.wholesale.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

/** Numbering format and defaults for one wholesale document type (e.g. QUOTATION). */
@Entity
@Table(name = "wholesale_document_settings")
public class WholesaleDocumentSettings {

    public static final String QUOTATION = "QUOTATION";
    public static final String SALES_RECEIPT = "SALES_RECEIPT";
    public static final String CREDIT_NOTE = "CREDIT_NOTE";
    public static final String DEBIT_NOTE = "DEBIT_NOTE";
    public static final String STOCK_ADJUSTMENT = "STOCK_ADJUSTMENT";

    @Id
    @Column(name = "doc_type", length = 30)
    private String docType;

    @Column(name = "prefix", nullable = false, length = 20) private String prefix;
    @Column(name = "separator", nullable = false, length = 3) private String separator = "/";
    @Column(name = "include_financial_year", nullable = false) private Boolean includeFinancialYear = true;
    @Column(name = "reset_each_financial_year", nullable = false) private Boolean resetEachFinancialYear = true;
    @Column(name = "number_padding", nullable = false) private Integer numberPadding = 4;
    @Column(name = "default_validity_days", nullable = false) private Integer defaultValidityDays = 15;
    @Column(name = "default_terms") private String defaultTerms;
    /** Credit Note: days until payment is due. */
    @Column(name = "default_due_days", nullable = false) private Integer defaultDueDays = 30;
    // Configurable document semantics (copied onto each document when it is issued)
    @Column(name = "display_label", length = 60) private String displayLabel;
    @Column(name = "print_title", length = 80) private String printTitle;
    @Enumerated(EnumType.STRING) @Column(name = "gst_classification", nullable = false, length = 20)
    private WholesaleGstClassification gstClassification = WholesaleGstClassification.TAX_INVOICE;
    @Enumerated(EnumType.STRING) @Column(name = "value_effect", nullable = false, length = 10)
    private WholesaleValueEffect valueEffect = WholesaleValueEffect.CHARGE;
    @Enumerated(EnumType.STRING) @Column(name = "stock_effect", nullable = false, length = 10)
    private WholesaleStockEffect stockEffect = WholesaleStockEffect.OUT;
    @Enumerated(EnumType.STRING) @Column(name = "reference_mode", nullable = false, length = 10)
    private WholesaleReferenceMode referenceMode = WholesaleReferenceMode.OPTIONAL;
    /** Comma-separated doc types (QUOTATION, SALES_RECEIPT, CREDIT_NOTE, DEBIT_NOTE) that may be referenced. */
    @Column(name = "allowed_reference_types", nullable = false, length = 120) private String allowedReferenceTypes = "";
    @Column(name = "reason_required", nullable = false) private Boolean reasonRequired = false;

    @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;
    @Column(name = "updated_by", length = 100) private String updatedBy;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = LocalDateTime.now();
    }

    public String getDocType() { return docType; }
    public void setDocType(String docType) { this.docType = docType; }
    public String getPrefix() { return prefix; }
    public void setPrefix(String prefix) { this.prefix = prefix; }
    public String getSeparator() { return separator; }
    public void setSeparator(String separator) { this.separator = separator; }
    public Boolean getIncludeFinancialYear() { return includeFinancialYear; }
    public void setIncludeFinancialYear(Boolean includeFinancialYear) { this.includeFinancialYear = includeFinancialYear; }
    public Boolean getResetEachFinancialYear() { return resetEachFinancialYear; }
    public void setResetEachFinancialYear(Boolean resetEachFinancialYear) { this.resetEachFinancialYear = resetEachFinancialYear; }
    public Integer getNumberPadding() { return numberPadding; }
    public void setNumberPadding(Integer numberPadding) { this.numberPadding = numberPadding; }
    public Integer getDefaultValidityDays() { return defaultValidityDays; }
    public void setDefaultValidityDays(Integer defaultValidityDays) { this.defaultValidityDays = defaultValidityDays; }
    public String getDefaultTerms() { return defaultTerms; }
    public void setDefaultTerms(String defaultTerms) { this.defaultTerms = defaultTerms; }
    public Integer getDefaultDueDays() { return defaultDueDays; }
    public void setDefaultDueDays(Integer defaultDueDays) { this.defaultDueDays = defaultDueDays; }
    public String getDisplayLabel() { return displayLabel; }
    public void setDisplayLabel(String displayLabel) { this.displayLabel = displayLabel; }
    public String getPrintTitle() { return printTitle; }
    public void setPrintTitle(String printTitle) { this.printTitle = printTitle; }
    public WholesaleGstClassification getGstClassification() { return gstClassification; }
    public void setGstClassification(WholesaleGstClassification gstClassification) { this.gstClassification = gstClassification; }
    public WholesaleValueEffect getValueEffect() { return valueEffect; }
    public void setValueEffect(WholesaleValueEffect valueEffect) { this.valueEffect = valueEffect; }
    public WholesaleStockEffect getStockEffect() { return stockEffect; }
    public void setStockEffect(WholesaleStockEffect stockEffect) { this.stockEffect = stockEffect; }
    public WholesaleReferenceMode getReferenceMode() { return referenceMode; }
    public void setReferenceMode(WholesaleReferenceMode referenceMode) { this.referenceMode = referenceMode; }
    public String getAllowedReferenceTypes() { return allowedReferenceTypes; }
    public void setAllowedReferenceTypes(String allowedReferenceTypes) { this.allowedReferenceTypes = allowedReferenceTypes; }
    public Boolean getReasonRequired() { return reasonRequired; }
    public void setReasonRequired(Boolean reasonRequired) { this.reasonRequired = reasonRequired; }

    public Set<String> allowedReferenceTypeSet() {
        Set<String> out = new LinkedHashSet<>();
        for (String t : (allowedReferenceTypes == null ? "" : allowedReferenceTypes).split(",")) {
            if (!t.isBlank()) out.add(t.trim());
        }
        return out;
    }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
}

package com.example.mybill.wholesale.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** Wholesale AI assistant rules and the business knowledge it may quote (single row). */
@Entity
@Table(name = "wholesale_assistant_settings")
public class WholesaleAssistantSettings {

    public static final short SINGLETON_ID = 1;
    public static final String DEFAULT_UNKNOWN_REPLY =
        "I don't have that information available right now. Our team can confirm it for you.";

    @Id
    @Column(name = "settings_id")
    private Short settingsId = SINGLETON_ID;

    @Column(name = "ordering_process") private String orderingProcess;
    @Column(name = "shipping_info") private String shippingInfo;
    @Column(name = "payment_terms") private String paymentTerms;
    @Column(name = "business_hours", length = 500) private String businessHours;
    @Column(name = "additional_info") private String additionalInfo;
    @Column(name = "unknown_reply", nullable = false, length = 500) private String unknownReply = DEFAULT_UNKNOWN_REPLY;
    @Column(name = "share_stock_quantity", nullable = false) private Boolean shareStockQuantity = false;
    @Column(name = "allow_quotation_drafts", nullable = false) private Boolean allowQuotationDrafts = true;
    @Column(name = "auto_issue_quotations", nullable = false) private Boolean autoIssueQuotations = false;
    @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;
    @Column(name = "updated_by", length = 100) private String updatedBy;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = LocalDateTime.now();
    }

    public String getOrderingProcess() { return orderingProcess; }
    public void setOrderingProcess(String orderingProcess) { this.orderingProcess = orderingProcess; }
    public String getShippingInfo() { return shippingInfo; }
    public void setShippingInfo(String shippingInfo) { this.shippingInfo = shippingInfo; }
    public String getPaymentTerms() { return paymentTerms; }
    public void setPaymentTerms(String paymentTerms) { this.paymentTerms = paymentTerms; }
    public String getBusinessHours() { return businessHours; }
    public void setBusinessHours(String businessHours) { this.businessHours = businessHours; }
    public String getAdditionalInfo() { return additionalInfo; }
    public void setAdditionalInfo(String additionalInfo) { this.additionalInfo = additionalInfo; }
    public String getUnknownReply() { return unknownReply; }
    public void setUnknownReply(String unknownReply) { this.unknownReply = unknownReply; }
    public Boolean getShareStockQuantity() { return shareStockQuantity; }
    public void setShareStockQuantity(Boolean shareStockQuantity) { this.shareStockQuantity = shareStockQuantity; }
    public Boolean getAllowQuotationDrafts() { return allowQuotationDrafts; }
    public void setAllowQuotationDrafts(Boolean allowQuotationDrafts) { this.allowQuotationDrafts = allowQuotationDrafts; }
    public Boolean getAutoIssueQuotations() { return autoIssueQuotations; }
    public void setAutoIssueQuotations(Boolean autoIssueQuotations) { this.autoIssueQuotations = autoIssueQuotations; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
}

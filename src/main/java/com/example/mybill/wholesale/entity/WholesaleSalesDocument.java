package com.example.mybill.wholesale.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Wholesale Sales Receipt / Credit Note. When created from a quotation, customer details, items, rates, GST and
 * totals are copied from it unchanged (sourceQuotation keeps the link); nothing is re-priced.
 */
@Entity
@Table(name = "wholesale_sales_documents")
public class WholesaleSalesDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "sales_document_id")
    private Integer salesDocumentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "doc_type", nullable = false, length = 20)
    private WholesaleSalesDocumentType docType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    /** Every document starts as DRAFT and is issued through the validated DRAFT → ISSUED transition. */
    private WholesaleSalesDocumentStatus status = WholesaleSalesDocumentStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_mode", length = 20)
    private WholesalePaymentMode paymentMode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_quotation_id")
    private WholesaleQuotation sourceQuotation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "wholesale_customer_id", nullable = false)
    private WholesaleCustomer customer;

    // Semantics copied from the document-type configuration (frozen once issued).
    @Column(name = "display_label", nullable = false, length = 60) private String displayLabel;
    @Column(name = "print_title", nullable = false, length = 80) private String printTitle;
    @Enumerated(EnumType.STRING) @Column(name = "gst_classification", nullable = false, length = 20)
    private WholesaleGstClassification gstClassification;
    @Enumerated(EnumType.STRING) @Column(name = "value_effect", nullable = false, length = 10)
    private WholesaleValueEffect valueEffect;
    @Enumerated(EnumType.STRING) @Column(name = "stock_effect", nullable = false, length = 10)
    private WholesaleStockEffect stockEffect;

    // Reference to the original document (internal, quotation or external invoice), number/date copied.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reference_document_id")
    private WholesaleSalesDocument referenceDocument;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reference_quotation_id")
    private WholesaleQuotation referenceQuotation;
    @Column(name = "reference_doc_type", length = 20) private String referenceDocType;
    @Column(name = "reference_number", length = 60) private String referenceNumber;
    @Column(name = "reference_date") private LocalDate referenceDate;
    @Column(name = "reference_external", nullable = false) private Boolean referenceExternal = false;
    @Column(name = "reason", length = 500) private String reason;

    @Column(name = "document_number", length = 40, unique = true)
    private String documentNumber;

    @Column(name = "document_date", nullable = false)
    private LocalDate documentDate;

    @Column(name = "customer_name", nullable = false, length = 150)
    private String customerName;

    @Column(name = "customer_business_name", length = 200)
    private String customerBusinessName;

    @Column(name = "customer_gst_number", length = 15)
    private String customerGstNumber;

    @Column(name = "customer_phone", length = 20)
    private String customerPhone;

    @Column(name = "customer_email", length = 150)
    private String customerEmail;

    @Column(name = "billing_address")
    private String billingAddress;

    @Column(name = "billing_city", length = 100)
    private String billingCity;

    @Column(name = "billing_state_code", length = 2)
    private String billingStateCode;

    @Column(name = "billing_state_name", length = 100)
    private String billingStateName;

    @Column(name = "billing_pin_code", length = 6)
    private String billingPinCode;

    @Column(name = "shipping_address")
    private String shippingAddress;

    @Column(name = "shipping_city", length = 100)
    private String shippingCity;

    @Column(name = "shipping_state_code", length = 2)
    private String shippingStateCode;

    @Column(name = "shipping_state_name", length = 100)
    private String shippingStateName;

    @Column(name = "shipping_pin_code", length = 6)
    private String shippingPinCode;

    @Column(name = "firm_snapshot_json", nullable = false)
    private String firmSnapshotJson;

    @Column(name = "firm_state_code", nullable = false, length = 2)
    private String firmStateCode;

    @Column(name = "place_of_supply_state_code", length = 2)
    private String placeOfSupplyStateCode;

    @Column(name = "interstate", nullable = false)
    private Boolean interstate = false;

    @Column(name = "gross_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal grossAmount = BigDecimal.ZERO;

    @Column(name = "discount_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Column(name = "subtotal", nullable = false, precision = 14, scale = 2)
    private BigDecimal subtotal = BigDecimal.ZERO;

    @Column(name = "other_charges_label", length = 60)
    private String otherChargesLabel;

    @Column(name = "other_charges_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal otherChargesAmount = BigDecimal.ZERO;

    @Column(name = "other_charges_gst_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal otherChargesGstPct = BigDecimal.ZERO;

    @Column(name = "other_charges_gst_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal otherChargesGstAmount = BigDecimal.ZERO;

    @Column(name = "cgst_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal cgstAmount = BigDecimal.ZERO;

    @Column(name = "sgst_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal sgstAmount = BigDecimal.ZERO;

    @Column(name = "igst_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal igstAmount = BigDecimal.ZERO;

    @Column(name = "gst_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal gstAmount = BigDecimal.ZERO;

    @Column(name = "round_off", nullable = false, precision = 6, scale = 2)
    private BigDecimal roundOff = BigDecimal.ZERO;

    @Column(name = "grand_total", nullable = false, precision = 14, scale = 2)
    private BigDecimal grandTotal = BigDecimal.ZERO;

    @Column(name = "payment_reference", length = 100)
    private String paymentReference;

    @Column(name = "amount_paid", nullable = false, precision = 14, scale = 2)
    private BigDecimal amountPaid = BigDecimal.ZERO;

    @Column(name = "payment_status", nullable = false, length = 10)
    private String paymentStatus;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "notes")
    private String notes;

    @Column(name = "terms")
    private String terms;

    @Column(name = "created_by", length = 100, updatable = false)
    private String createdBy;

    @Column(name = "converted_at")
    private LocalDateTime convertedAt;

    @Column(name = "converted_by", length = 100)
    private String convertedBy;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "cancelled_by", length = 100)
    private String cancelledBy;

    @Column(name = "cancel_reason", length = 255)
    private String cancelReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "issued_at")
    private LocalDateTime issuedAt;

    @Column(name = "issued_by", length = 100)
    private String issuedBy;

    @OneToMany(mappedBy = "document", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo ASC")
    private List<WholesaleSalesDocumentItem> items = new ArrayList<>();

    @OneToMany(mappedBy = "document", cascade = CascadeType.ALL)
    @OrderBy("paymentDate ASC, paymentId ASC")
    private List<WholesaleSalesPayment> payments = new ArrayList<>();

    @OneToMany(mappedBy = "document", cascade = CascadeType.ALL)
    @OrderBy("eventAt ASC, eventId ASC")
    private List<WholesaleDocumentEvent> events = new ArrayList<>();

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Integer getSalesDocumentId() { return salesDocumentId; }

    public WholesaleSalesDocumentType getDocType() { return docType; }
    public void setDocType(WholesaleSalesDocumentType docType) { this.docType = docType; }

    public WholesaleSalesDocumentStatus getStatus() { return status; }
    public void setStatus(WholesaleSalesDocumentStatus status) { this.status = status; }

    public WholesalePaymentMode getPaymentMode() { return paymentMode; }
    public void setPaymentMode(WholesalePaymentMode paymentMode) { this.paymentMode = paymentMode; }

    public WholesaleQuotation getSourceQuotation() { return sourceQuotation; }
    public void setSourceQuotation(WholesaleQuotation sourceQuotation) { this.sourceQuotation = sourceQuotation; }

    public WholesaleCustomer getCustomer() { return customer; }
    public void setCustomer(WholesaleCustomer customer) { this.customer = customer; }

    public String getDocumentNumber() { return documentNumber; }
    public void setDocumentNumber(String documentNumber) { this.documentNumber = documentNumber; }

    public LocalDate getDocumentDate() { return documentDate; }
    public void setDocumentDate(LocalDate documentDate) { this.documentDate = documentDate; }

    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }

    public String getCustomerBusinessName() { return customerBusinessName; }
    public void setCustomerBusinessName(String customerBusinessName) { this.customerBusinessName = customerBusinessName; }

    public String getCustomerGstNumber() { return customerGstNumber; }
    public void setCustomerGstNumber(String customerGstNumber) { this.customerGstNumber = customerGstNumber; }

    public String getCustomerPhone() { return customerPhone; }
    public void setCustomerPhone(String customerPhone) { this.customerPhone = customerPhone; }

    public String getCustomerEmail() { return customerEmail; }
    public void setCustomerEmail(String customerEmail) { this.customerEmail = customerEmail; }

    public String getBillingAddress() { return billingAddress; }
    public void setBillingAddress(String billingAddress) { this.billingAddress = billingAddress; }

    public String getBillingCity() { return billingCity; }
    public void setBillingCity(String billingCity) { this.billingCity = billingCity; }

    public String getBillingStateCode() { return billingStateCode; }
    public void setBillingStateCode(String billingStateCode) { this.billingStateCode = billingStateCode; }

    public String getBillingStateName() { return billingStateName; }
    public void setBillingStateName(String billingStateName) { this.billingStateName = billingStateName; }

    public String getBillingPinCode() { return billingPinCode; }
    public void setBillingPinCode(String billingPinCode) { this.billingPinCode = billingPinCode; }

    public String getShippingAddress() { return shippingAddress; }
    public void setShippingAddress(String shippingAddress) { this.shippingAddress = shippingAddress; }

    public String getShippingCity() { return shippingCity; }
    public void setShippingCity(String shippingCity) { this.shippingCity = shippingCity; }

    public String getShippingStateCode() { return shippingStateCode; }
    public void setShippingStateCode(String shippingStateCode) { this.shippingStateCode = shippingStateCode; }

    public String getShippingStateName() { return shippingStateName; }
    public void setShippingStateName(String shippingStateName) { this.shippingStateName = shippingStateName; }

    public String getShippingPinCode() { return shippingPinCode; }
    public void setShippingPinCode(String shippingPinCode) { this.shippingPinCode = shippingPinCode; }

    public String getFirmSnapshotJson() { return firmSnapshotJson; }
    public void setFirmSnapshotJson(String firmSnapshotJson) { this.firmSnapshotJson = firmSnapshotJson; }

    public String getFirmStateCode() { return firmStateCode; }
    public void setFirmStateCode(String firmStateCode) { this.firmStateCode = firmStateCode; }

    public String getPlaceOfSupplyStateCode() { return placeOfSupplyStateCode; }
    public void setPlaceOfSupplyStateCode(String placeOfSupplyStateCode) { this.placeOfSupplyStateCode = placeOfSupplyStateCode; }

    public Boolean getInterstate() { return interstate; }
    public void setInterstate(Boolean interstate) { this.interstate = interstate; }

    public BigDecimal getGrossAmount() { return grossAmount; }
    public void setGrossAmount(BigDecimal grossAmount) { this.grossAmount = grossAmount; }

    public BigDecimal getDiscountAmount() { return discountAmount; }
    public void setDiscountAmount(BigDecimal discountAmount) { this.discountAmount = discountAmount; }

    public BigDecimal getSubtotal() { return subtotal; }
    public void setSubtotal(BigDecimal subtotal) { this.subtotal = subtotal; }

    public String getOtherChargesLabel() { return otherChargesLabel; }
    public void setOtherChargesLabel(String otherChargesLabel) { this.otherChargesLabel = otherChargesLabel; }

    public BigDecimal getOtherChargesAmount() { return otherChargesAmount; }
    public void setOtherChargesAmount(BigDecimal otherChargesAmount) { this.otherChargesAmount = otherChargesAmount; }

    public BigDecimal getOtherChargesGstPct() { return otherChargesGstPct; }
    public void setOtherChargesGstPct(BigDecimal otherChargesGstPct) { this.otherChargesGstPct = otherChargesGstPct; }

    public BigDecimal getOtherChargesGstAmount() { return otherChargesGstAmount; }
    public void setOtherChargesGstAmount(BigDecimal otherChargesGstAmount) { this.otherChargesGstAmount = otherChargesGstAmount; }

    public BigDecimal getCgstAmount() { return cgstAmount; }
    public void setCgstAmount(BigDecimal cgstAmount) { this.cgstAmount = cgstAmount; }

    public BigDecimal getSgstAmount() { return sgstAmount; }
    public void setSgstAmount(BigDecimal sgstAmount) { this.sgstAmount = sgstAmount; }

    public BigDecimal getIgstAmount() { return igstAmount; }
    public void setIgstAmount(BigDecimal igstAmount) { this.igstAmount = igstAmount; }

    public BigDecimal getGstAmount() { return gstAmount; }
    public void setGstAmount(BigDecimal gstAmount) { this.gstAmount = gstAmount; }

    public BigDecimal getRoundOff() { return roundOff; }
    public void setRoundOff(BigDecimal roundOff) { this.roundOff = roundOff; }

    public BigDecimal getGrandTotal() { return grandTotal; }
    public void setGrandTotal(BigDecimal grandTotal) { this.grandTotal = grandTotal; }

    public String getPaymentReference() { return paymentReference; }
    public void setPaymentReference(String paymentReference) { this.paymentReference = paymentReference; }

    public BigDecimal getAmountPaid() { return amountPaid; }
    public void setAmountPaid(BigDecimal amountPaid) { this.amountPaid = amountPaid; }

    public String getPaymentStatus() { return paymentStatus; }
    public void setPaymentStatus(String paymentStatus) { this.paymentStatus = paymentStatus; }

    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public String getTerms() { return terms; }
    public void setTerms(String terms) { this.terms = terms; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public LocalDateTime getConvertedAt() { return convertedAt; }
    public void setConvertedAt(LocalDateTime convertedAt) { this.convertedAt = convertedAt; }

    public String getConvertedBy() { return convertedBy; }
    public void setConvertedBy(String convertedBy) { this.convertedBy = convertedBy; }

    public LocalDateTime getCancelledAt() { return cancelledAt; }
    public void setCancelledAt(LocalDateTime cancelledAt) { this.cancelledAt = cancelledAt; }

    public String getCancelledBy() { return cancelledBy; }
    public void setCancelledBy(String cancelledBy) { this.cancelledBy = cancelledBy; }

    public String getCancelReason() { return cancelReason; }
    public void setCancelReason(String cancelReason) { this.cancelReason = cancelReason; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public List<WholesaleSalesDocumentItem> getItems() { return items; }

    public List<WholesaleSalesPayment> getPayments() { return payments; }

    public List<WholesaleDocumentEvent> getEvents() { return events; }

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
    public WholesaleSalesDocument getReferenceDocument() { return referenceDocument; }
    public void setReferenceDocument(WholesaleSalesDocument referenceDocument) { this.referenceDocument = referenceDocument; }
    public WholesaleQuotation getReferenceQuotation() { return referenceQuotation; }
    public void setReferenceQuotation(WholesaleQuotation referenceQuotation) { this.referenceQuotation = referenceQuotation; }
    public String getReferenceDocType() { return referenceDocType; }
    public void setReferenceDocType(String referenceDocType) { this.referenceDocType = referenceDocType; }
    public String getReferenceNumber() { return referenceNumber; }
    public void setReferenceNumber(String referenceNumber) { this.referenceNumber = referenceNumber; }
    public LocalDate getReferenceDate() { return referenceDate; }
    public void setReferenceDate(LocalDate referenceDate) { this.referenceDate = referenceDate; }
    public Boolean getReferenceExternal() { return referenceExternal; }
    public void setReferenceExternal(Boolean referenceExternal) { this.referenceExternal = referenceExternal; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    /** Payments only make sense where the customer owes money. */
    public boolean isChargeable() { return valueEffect != WholesaleValueEffect.CREDIT; }

    public LocalDateTime getIssuedAt() { return issuedAt; }
    public void setIssuedAt(LocalDateTime issuedAt) { this.issuedAt = issuedAt; }
    public String getIssuedBy() { return issuedBy; }
    public void setIssuedBy(String issuedBy) { this.issuedBy = issuedBy; }
}

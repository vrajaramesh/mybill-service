package com.example.mybill.wholesale.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Wholesale quotation. Customer and firm details are snapshots (refreshed while DRAFT, frozen on ISSUE);
 * items keep the actual rate used, so later customer/profile/price-rule changes never alter it.
 */
@Entity
@Table(name = "wholesale_quotations")
public class WholesaleQuotation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "quotation_id")
    private Integer quotationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private WholesaleQuotationStatus status = WholesaleQuotationStatus.DRAFT;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "wholesale_customer_id", nullable = false)
    private WholesaleCustomer customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "duplicated_from_id")
    private WholesaleQuotation duplicatedFrom;

    @Column(name = "quotation_number", length = 40, unique = true)
    private String quotationNumber;

    @Column(name = "quotation_date", nullable = false)
    private LocalDate quotationDate;

    @Column(name = "valid_until", nullable = false)
    private LocalDate validUntil;

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

    @Column(name = "notes")
    private String notes;

    @Column(name = "terms")
    private String terms;

    @Column(name = "cancel_reason", length = 255)
    private String cancelReason;

    @Column(name = "created_by", length = 100, updatable = false)
    private String createdBy;

    @Column(name = "issued_at")
    private LocalDateTime issuedAt;

    @Column(name = "issued_by", length = 100)
    private String issuedBy;

    @Column(name = "status_changed_at")
    private LocalDateTime statusChangedAt;

    @Column(name = "status_changed_by", length = 100)
    private String statusChangedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "quotation", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo ASC")
    private List<WholesaleQuotationItem> items = new ArrayList<>();

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Integer getQuotationId() { return quotationId; }

    public WholesaleQuotationStatus getStatus() { return status; }
    public void setStatus(WholesaleQuotationStatus status) { this.status = status; }

    public WholesaleCustomer getCustomer() { return customer; }
    public void setCustomer(WholesaleCustomer customer) { this.customer = customer; }

    public WholesaleQuotation getDuplicatedFrom() { return duplicatedFrom; }
    public void setDuplicatedFrom(WholesaleQuotation duplicatedFrom) { this.duplicatedFrom = duplicatedFrom; }

    public String getQuotationNumber() { return quotationNumber; }
    public void setQuotationNumber(String quotationNumber) { this.quotationNumber = quotationNumber; }

    public LocalDate getQuotationDate() { return quotationDate; }
    public void setQuotationDate(LocalDate quotationDate) { this.quotationDate = quotationDate; }

    public LocalDate getValidUntil() { return validUntil; }
    public void setValidUntil(LocalDate validUntil) { this.validUntil = validUntil; }

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

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public String getTerms() { return terms; }
    public void setTerms(String terms) { this.terms = terms; }

    public String getCancelReason() { return cancelReason; }
    public void setCancelReason(String cancelReason) { this.cancelReason = cancelReason; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public LocalDateTime getIssuedAt() { return issuedAt; }
    public void setIssuedAt(LocalDateTime issuedAt) { this.issuedAt = issuedAt; }

    public String getIssuedBy() { return issuedBy; }
    public void setIssuedBy(String issuedBy) { this.issuedBy = issuedBy; }

    public LocalDateTime getStatusChangedAt() { return statusChangedAt; }
    public void setStatusChangedAt(LocalDateTime statusChangedAt) { this.statusChangedAt = statusChangedAt; }

    public String getStatusChangedBy() { return statusChangedBy; }
    public void setStatusChangedBy(String statusChangedBy) { this.statusChangedBy = statusChangedBy; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public List<WholesaleQuotationItem> getItems() { return items; }
}

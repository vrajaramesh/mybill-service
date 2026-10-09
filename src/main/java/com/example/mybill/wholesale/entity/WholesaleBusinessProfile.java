package com.example.mybill.wholesale.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * The firm's wholesale letterhead + payment details (single row, id 1). Used by quotations, credit invoices,
 * sales receipts and debit notes. Documents must copy these values when issued (they may change later).
 */
@Entity
@Table(name = "wholesale_business_profile")
public class WholesaleBusinessProfile {

    public static final short SINGLETON_ID = 1;

    @Id
    @Column(name = "profile_id")
    private Short profileId = SINGLETON_ID;

    @Column(name = "firm_name", length = 200) private String firmName;
    @Column(name = "address") private String address;
    @Column(name = "city", length = 100) private String city;
    @Column(name = "state_code", length = 2) private String stateCode;
    @Column(name = "state_name", length = 100) private String stateName;
    @Column(name = "pin_code", length = 6) private String pinCode;
    @Column(name = "gst_number", length = 15) private String gstNumber;
    @Column(name = "phone", length = 20) private String phone;
    @Column(name = "email", length = 150) private String email;
    @Column(name = "website", length = 255) private String website;
    @Column(name = "logo_url", length = 500) private String logoUrl;
    @Column(name = "logo_public_id", length = 255) private String logoPublicId;

    @Column(name = "bank_name", length = 100) private String bankName;
    @Column(name = "bank_account_name", length = 150) private String bankAccountName;
    @Column(name = "bank_account_number", length = 18) private String bankAccountNumber;
    @Column(name = "bank_ifsc", length = 11) private String bankIfsc;
    @Column(name = "bank_branch", length = 150) private String bankBranch;

    @Column(name = "upi_id", length = 100) private String upiId;
    @Column(name = "upi_qr_url", length = 500) private String upiQrUrl;
    @Column(name = "upi_qr_public_id", length = 255) private String upiQrPublicId;

    @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;
    @Column(name = "updated_by", length = 100) private String updatedBy;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Short getProfileId() { return profileId; }

    public String getFirmName() { return firmName; }
    public void setFirmName(String firmName) { this.firmName = firmName; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
    public String getStateCode() { return stateCode; }
    public void setStateCode(String stateCode) { this.stateCode = stateCode; }
    public String getStateName() { return stateName; }
    public void setStateName(String stateName) { this.stateName = stateName; }
    public String getPinCode() { return pinCode; }
    public void setPinCode(String pinCode) { this.pinCode = pinCode; }
    public String getGstNumber() { return gstNumber; }
    public void setGstNumber(String gstNumber) { this.gstNumber = gstNumber; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getWebsite() { return website; }
    public void setWebsite(String website) { this.website = website; }
    public String getLogoUrl() { return logoUrl; }
    public void setLogoUrl(String logoUrl) { this.logoUrl = logoUrl; }
    public String getLogoPublicId() { return logoPublicId; }
    public void setLogoPublicId(String logoPublicId) { this.logoPublicId = logoPublicId; }

    public String getBankName() { return bankName; }
    public void setBankName(String bankName) { this.bankName = bankName; }
    public String getBankAccountName() { return bankAccountName; }
    public void setBankAccountName(String bankAccountName) { this.bankAccountName = bankAccountName; }
    public String getBankAccountNumber() { return bankAccountNumber; }
    public void setBankAccountNumber(String bankAccountNumber) { this.bankAccountNumber = bankAccountNumber; }
    public String getBankIfsc() { return bankIfsc; }
    public void setBankIfsc(String bankIfsc) { this.bankIfsc = bankIfsc; }
    public String getBankBranch() { return bankBranch; }
    public void setBankBranch(String bankBranch) { this.bankBranch = bankBranch; }

    public String getUpiId() { return upiId; }
    public void setUpiId(String upiId) { this.upiId = upiId; }
    public String getUpiQrUrl() { return upiQrUrl; }
    public void setUpiQrUrl(String upiQrUrl) { this.upiQrUrl = upiQrUrl; }
    public String getUpiQrPublicId() { return upiQrPublicId; }
    public void setUpiQrPublicId(String upiQrPublicId) { this.upiQrPublicId = upiQrPublicId; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
}

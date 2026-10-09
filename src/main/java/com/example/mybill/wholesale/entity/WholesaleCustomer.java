package com.example.mybill.wholesale.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** B2B customer for wholesale documents. Independent of the retail `customers` table. */
@Entity
@Table(name = "wholesale_customers")
public class WholesaleCustomer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "wholesale_customer_id")
    private Integer wholesaleCustomerId;

    @Column(name = "customer_name", nullable = false, length = 150)
    private String customerName;

    @Column(name = "business_name", length = 200)
    private String businessName;

    @Enumerated(EnumType.STRING)
    @Column(name = "customer_type", nullable = false, length = 20)
    private WholesaleCustomerType customerType = WholesaleCustomerType.DEFAULT;

    /** Upper-case 15-character GSTIN, unique across wholesale customers. */
    @Column(name = "gst_number", length = 15)
    private String gstNumber;

    @Column(name = "phone", length = 20)
    private String phone;

    @Column(name = "email", length = 150)
    private String email;

    @Column(name = "billing_address")
    private String billingAddress;

    @Column(name = "city", length = 100)
    private String city;

    @Column(name = "state_code", length = 2)
    private String stateCode;

    @Column(name = "state_name", length = 100)
    private String stateName;

    @Column(name = "pin_code", length = 6)
    private String pinCode;

    @Column(name = "shipping_same_as_billing", nullable = false)
    private Boolean shippingSameAsBilling = true;

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

    @Column(name = "notes")
    private String notes;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Integer getWholesaleCustomerId() { return wholesaleCustomerId; }

    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }

    public String getBusinessName() { return businessName; }
    public void setBusinessName(String businessName) { this.businessName = businessName; }

    public WholesaleCustomerType getCustomerType() { return customerType; }
    public void setCustomerType(WholesaleCustomerType customerType) { this.customerType = customerType; }

    public String getGstNumber() { return gstNumber; }
    public void setGstNumber(String gstNumber) { this.gstNumber = gstNumber; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getBillingAddress() { return billingAddress; }
    public void setBillingAddress(String billingAddress) { this.billingAddress = billingAddress; }

    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }

    public String getStateCode() { return stateCode; }
    public void setStateCode(String stateCode) { this.stateCode = stateCode; }

    public String getStateName() { return stateName; }
    public void setStateName(String stateName) { this.stateName = stateName; }

    public String getPinCode() { return pinCode; }
    public void setPinCode(String pinCode) { this.pinCode = pinCode; }

    public Boolean getShippingSameAsBilling() { return shippingSameAsBilling; }
    public void setShippingSameAsBilling(Boolean shippingSameAsBilling) { this.shippingSameAsBilling = shippingSameAsBilling; }

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

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}

package com.example.mybill.boutique.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * Agent view of the shared retail `customers` table: identity columns only.
 * Kept separate from the retail Customer entity so the retail UI's full-entity saves never
 * overwrite phone_e164 / instagram_user_id / source, and vice versa.
 */
@Entity
@Table(name = "customers")
public class BoutiqueCustomer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "customer_id")
    private Integer customerId;

    @Column(name = "customer_name", length = 100)
    private String customerName;

    /** Legacy free-text phone shown in the retail UI (10-digit national number for Indian mobiles). */
    @Column(name = "phone", length = 15)
    private String phone;

    /** E.164, e.g. +919876543210. Unique per firm. */
    @Column(name = "phone_e164", length = 16)
    private String phoneE164;

    @Column(name = "instagram_user_id", length = 64)
    private String instagramUserId;

    /** SHOP, WHATSAPP or INSTAGRAM: where the customer was first seen. */
    @Column(name = "source", nullable = false, length = 20)
    private String source = "SHOP";

    /** Opted in (true) / out (false) of WhatsApp order updates and offers; NULL = never asked. */
    @Column(name = "whatsapp_updates_consent")
    private Boolean whatsappUpdatesConsent;

    @Column(name = "consent_at")
    private LocalDateTime consentAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public Integer getCustomerId() { return customerId; }
    public void setCustomerId(Integer customerId) { this.customerId = customerId; }
    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getPhoneE164() { return phoneE164; }
    public void setPhoneE164(String phoneE164) { this.phoneE164 = phoneE164; }
    public String getInstagramUserId() { return instagramUserId; }
    public void setInstagramUserId(String instagramUserId) { this.instagramUserId = instagramUserId; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public Boolean getWhatsappUpdatesConsent() { return whatsappUpdatesConsent; }
    public void setWhatsappUpdatesConsent(Boolean v) { this.whatsappUpdatesConsent = v; }
    public LocalDateTime getConsentAt() { return consentAt; }
    public void setConsentAt(LocalDateTime v) { this.consentAt = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}

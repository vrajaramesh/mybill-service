package com.example.mybill.boutique.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** One Razorpay Payment Link for a boutique order (an order can have several: expired links get replaced). */
@Entity
@Table(name = "boutique_order_payments")
public class BoutiqueOrderPayment {

    public static final String CREATED = "created", PAID = "paid", EXPIRED = "expired", CANCELLED = "cancelled";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "payment_id")
    private Integer paymentId;

    @Column(name = "order_id", nullable = false)
    private Integer orderId;

    /** Razorpay "plink_..." id. */
    @Column(name = "razorpay_payment_link_id", length = 64)
    private String razorpayPaymentLinkId;

    /** orderNo, then orderNo-2, orderNo-3 ... for replacement links (Razorpay requires it unique, max 40 chars). */
    @Column(name = "reference_id", nullable = false, length = 40)
    private String referenceId;

    @Column(name = "short_url", length = 255)
    private String shortUrl;

    /** Amount requested, in paise, taken from the order total in the DB. */
    @Column(name = "amount_paise", nullable = false)
    private Long amountPaise;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "INR";

    /** created | paid | expired | cancelled */
    @Column(name = "status", nullable = false, length = 20)
    private String status = CREATED;

    @Column(name = "razorpay_payment_id", length = 64)
    private String razorpayPaymentId;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "needs_review", nullable = false)
    private Boolean needsReview = false;

    @Column(name = "review_reason")
    private String reviewReason;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public boolean isActive(LocalDateTime now) {
        return CREATED.equals(status) && expiresAt != null && expiresAt.isAfter(now.plusMinutes(1));
    }

    public Integer getPaymentId() { return paymentId; }
    public void setPaymentId(Integer v) { this.paymentId = v; }
    public Integer getOrderId() { return orderId; }
    public void setOrderId(Integer v) { this.orderId = v; }
    public String getRazorpayPaymentLinkId() { return razorpayPaymentLinkId; }
    public void setRazorpayPaymentLinkId(String v) { this.razorpayPaymentLinkId = v; }
    public String getReferenceId() { return referenceId; }
    public void setReferenceId(String v) { this.referenceId = v; }
    public String getShortUrl() { return shortUrl; }
    public void setShortUrl(String v) { this.shortUrl = v; }
    public Long getAmountPaise() { return amountPaise; }
    public void setAmountPaise(Long v) { this.amountPaise = v; }
    public String getCurrency() { return currency; }
    public void setCurrency(String v) { this.currency = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getRazorpayPaymentId() { return razorpayPaymentId; }
    public void setRazorpayPaymentId(String v) { this.razorpayPaymentId = v; }
    public LocalDateTime getPaidAt() { return paidAt; }
    public void setPaidAt(LocalDateTime v) { this.paidAt = v; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime v) { this.expiresAt = v; }
    public Boolean getNeedsReview() { return needsReview; }
    public void setNeedsReview(Boolean v) { this.needsReview = v; }
    public String getReviewReason() { return reviewReason; }
    public void setReviewReason(String v) { this.reviewReason = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime v) { this.createdAt = v; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime v) { this.updatedAt = v; }
}

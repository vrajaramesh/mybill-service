package com.example.mybill.wholesale.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** A payment against a wholesale sales document. Never deleted: mistakes are VOIDED with a reason. */
@Entity
@Table(name = "wholesale_sales_payments")
public class WholesaleSalesPayment {

    public static final String RECORDED = "RECORDED";
    public static final String VOIDED = "VOIDED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "payment_id")
    private Integer paymentId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sales_document_id", nullable = false)
    private WholesaleSalesDocument document;

    @Column(name = "payment_date", nullable = false) private LocalDate paymentDate;
    @Column(name = "amount", nullable = false, precision = 14, scale = 2) private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_mode", nullable = false, length = 20)
    private WholesalePaymentMode paymentMode;

    @Column(name = "reference", length = 100) private String reference;
    @Column(name = "notes", length = 255) private String notes;
    @Column(name = "status", nullable = false, length = 10) private String status = RECORDED;
    @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;
    @Column(name = "created_by", length = 100, updatable = false) private String createdBy;
    @Column(name = "voided_at") private LocalDateTime voidedAt;
    @Column(name = "voided_by", length = 100) private String voidedBy;
    @Column(name = "void_reason", length = 255) private String voidReason;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public boolean isRecorded() { return RECORDED.equals(status); }

    public Integer getPaymentId() { return paymentId; }
    public WholesaleSalesDocument getDocument() { return document; }
    public void setDocument(WholesaleSalesDocument document) { this.document = document; }
    public LocalDate getPaymentDate() { return paymentDate; }
    public void setPaymentDate(LocalDate paymentDate) { this.paymentDate = paymentDate; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public WholesalePaymentMode getPaymentMode() { return paymentMode; }
    public void setPaymentMode(WholesalePaymentMode paymentMode) { this.paymentMode = paymentMode; }
    public String getReference() { return reference; }
    public void setReference(String reference) { this.reference = reference; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public LocalDateTime getVoidedAt() { return voidedAt; }
    public void setVoidedAt(LocalDateTime voidedAt) { this.voidedAt = voidedAt; }
    public String getVoidedBy() { return voidedBy; }
    public void setVoidedBy(String voidedBy) { this.voidedBy = voidedBy; }
    public String getVoidReason() { return voidReason; }
    public void setVoidReason(String voidReason) { this.voidReason = voidReason; }
}

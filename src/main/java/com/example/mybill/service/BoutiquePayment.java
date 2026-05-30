package com.example.mybill.service;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "boutique_payments")
public class BoutiquePayment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "payment_id")
    private Integer paymentId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    @JsonIgnore
    private StitchingOrder order;

    @Column(name = "payment_date")
    private LocalDate paymentDate;

    @Column(name = "amount", precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(name = "payment_method", length = 20)
    private String paymentMethod = "CASH";

    @Column(name = "payment_type", length = 20)
    private String paymentType = "ADVANCE"; // ADVANCE, PARTIAL, FINAL

    @Column(name = "notes", length = 200)
    private String notes;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public Integer getPaymentId() { return paymentId; }
    public void setPaymentId(Integer v) { this.paymentId = v; }
    public StitchingOrder getOrder() { return order; }
    public void setOrder(StitchingOrder v) { this.order = v; }
    public LocalDate getPaymentDate() { return paymentDate; }
    public void setPaymentDate(LocalDate v) { this.paymentDate = v; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal v) { this.amount = v; }
    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String v) { this.paymentMethod = v; }
    public String getPaymentType() { return paymentType; }
    public void setPaymentType(String v) { this.paymentType = v; }
    public String getNotes() { return notes; }
    public void setNotes(String v) { this.notes = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime v) { this.createdAt = v; }
}

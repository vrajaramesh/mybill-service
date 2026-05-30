package com.example.mybill.service;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "boutique_extra_charges")
public class ExtraCharge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "charge_id")
    private Integer chargeId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    @JsonIgnore
    private StitchingOrder order;

    @Column(name = "description", length = 255)
    private String description;

    @Column(name = "amount", precision = 10, scale = 2)
    private BigDecimal amount = BigDecimal.ZERO;

    @Column(name = "added_at")
    private LocalDateTime addedAt;

    public Integer getChargeId() { return chargeId; }
    public void setChargeId(Integer v) { this.chargeId = v; }
    public StitchingOrder getOrder() { return order; }
    public void setOrder(StitchingOrder v) { this.order = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal v) { this.amount = v; }
    public LocalDateTime getAddedAt() { return addedAt; }
    public void setAddedAt(LocalDateTime v) { this.addedAt = v; }
}

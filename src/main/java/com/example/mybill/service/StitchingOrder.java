package com.example.mybill.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "stitching_orders")
public class StitchingOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "order_id")
    private Integer orderId;

    @Column(name = "order_number", unique = true, length = 30)
    private String orderNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
    private Customer customer;

    @Column(name = "order_date")
    private LocalDate orderDate;

    @Column(name = "delivery_date")
    private LocalDate deliveryDate;

    @Column(name = "priority", length = 20)
    private String priority = "NORMAL"; // NORMAL, URGENT, EXPRESS

    @Column(name = "status", length = 30)
    private String status = "RECEIVED"; // RECEIVED, CUTTING, STITCHING, FINISHING, READY, DELIVERED, CANCELLED

    @Column(name = "total_amount", precision = 10, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(name = "advance_paid", precision = 10, scale = 2)
    private BigDecimal advancePaid = BigDecimal.ZERO;

    @Column(name = "balance_amount", precision = 10, scale = 2)
    private BigDecimal balanceAmount = BigDecimal.ZERO;

    @Column(name = "notes")
    private String notes;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<StitchingOrderItem> items = new ArrayList<>();

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<BoutiquePayment> payments = new ArrayList<>();

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<ExtraCharge> extraCharges = new ArrayList<>();

    public Integer getOrderId() { return orderId; }
    public void setOrderId(Integer v) { this.orderId = v; }
    public String getOrderNumber() { return orderNumber; }
    public void setOrderNumber(String v) { this.orderNumber = v; }
    public Customer getCustomer() { return customer; }
    public void setCustomer(Customer v) { this.customer = v; }
    public LocalDate getOrderDate() { return orderDate; }
    public void setOrderDate(LocalDate v) { this.orderDate = v; }
    public LocalDate getDeliveryDate() { return deliveryDate; }
    public void setDeliveryDate(LocalDate v) { this.deliveryDate = v; }
    public String getPriority() { return priority; }
    public void setPriority(String v) { this.priority = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal v) { this.totalAmount = v; }
    public BigDecimal getAdvancePaid() { return advancePaid; }
    public void setAdvancePaid(BigDecimal v) { this.advancePaid = v; }
    public BigDecimal getBalanceAmount() { return balanceAmount; }
    public void setBalanceAmount(BigDecimal v) { this.balanceAmount = v; }
    public String getNotes() { return notes; }
    public void setNotes(String v) { this.notes = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime v) { this.createdAt = v; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime v) { this.updatedAt = v; }
    public List<StitchingOrderItem> getItems() { return items; }
    public void setItems(List<StitchingOrderItem> v) { this.items = v; }
    public List<BoutiquePayment> getPayments() { return payments; }
    public void setPayments(List<BoutiquePayment> v) { this.payments = v; }
    public List<ExtraCharge> getExtraCharges() { return extraCharges; }
    public void setExtraCharges(List<ExtraCharge> v) { this.extraCharges = v; }
}

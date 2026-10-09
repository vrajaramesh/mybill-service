package com.example.mybill.wholesale.entity;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Source document for manual stock corrections (and the one-time opening balance). */
@Entity
@Table(name = "wholesale_stock_adjustments")
public class WholesaleStockAdjustment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "adjustment_id")
    private Integer adjustmentId;

    @Column(name = "adjustment_number", nullable = false, length = 40, unique = true) private String adjustmentNumber;
    @Column(name = "adjustment_date", nullable = false) private LocalDate adjustmentDate;
    @Column(name = "reason", nullable = false, length = 500) private String reason;
    @Column(name = "is_opening", nullable = false) private Boolean isOpening = false;
    @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;
    @Column(name = "created_by", length = 100, updatable = false) private String createdBy;

    @OneToMany(mappedBy = "adjustment", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("adjustmentItemId ASC")
    private List<WholesaleStockAdjustmentItem> items = new ArrayList<>();

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public Integer getAdjustmentId() { return adjustmentId; }
    public String getAdjustmentNumber() { return adjustmentNumber; }
    public void setAdjustmentNumber(String adjustmentNumber) { this.adjustmentNumber = adjustmentNumber; }
    public LocalDate getAdjustmentDate() { return adjustmentDate; }
    public void setAdjustmentDate(LocalDate adjustmentDate) { this.adjustmentDate = adjustmentDate; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Boolean getIsOpening() { return isOpening; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public List<WholesaleStockAdjustmentItem> getItems() { return items; }
}

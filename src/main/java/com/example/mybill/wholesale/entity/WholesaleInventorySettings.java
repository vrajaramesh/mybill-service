package com.example.mybill.wholesale.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** Wholesale inventory rules (single row). */
@Entity
@Table(name = "wholesale_inventory_settings")
public class WholesaleInventorySettings {

    public static final short SINGLETON_ID = 1;

    @Id
    @Column(name = "settings_id")
    private Short settingsId = SINGLETON_ID;

    /** false (default) = a movement that would take a product below zero is refused. */
    @Column(name = "allow_negative_stock", nullable = false) private Boolean allowNegativeStock = false;
    @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;
    @Column(name = "updated_by", length = 100) private String updatedBy;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = LocalDateTime.now();
    }

    public Boolean getAllowNegativeStock() { return allowNegativeStock; }
    public void setAllowNegativeStock(Boolean allowNegativeStock) { this.allowNegativeStock = allowNegativeStock; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
}

package com.example.mybill.wholesale.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Quantity-based selling price for a wholesale product: [minQuantity, maxQuantity] -> price per unit.
 * Ranges are inclusive; maxQuantity null means "and above". Prices are exclusive of GST.
 */
@Entity
@Table(name = "wholesale_product_price_rules")
public class WholesaleProductPriceRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "price_rule_id")
    private Integer priceRuleId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "wholesale_product_id", nullable = false)
    private WholesaleProduct product;

    @Column(name = "min_quantity", nullable = false, precision = 12, scale = 3)
    private BigDecimal minQuantity;

    @Column(name = "max_quantity", precision = 12, scale = 3)
    private BigDecimal maxQuantity;

    @Column(name = "price", nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    /** Null = use the product's GST %. */
    @Column(name = "gst_pct", precision = 5, scale = 2)
    private BigDecimal gstPct;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "notes", length = 255)
    private String notes;

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

    public Integer getPriceRuleId() { return priceRuleId; }

    public WholesaleProduct getProduct() { return product; }
    public void setProduct(WholesaleProduct product) { this.product = product; }

    public BigDecimal getMinQuantity() { return minQuantity; }
    public void setMinQuantity(BigDecimal minQuantity) { this.minQuantity = minQuantity; }

    public BigDecimal getMaxQuantity() { return maxQuantity; }
    public void setMaxQuantity(BigDecimal maxQuantity) { this.maxQuantity = maxQuantity; }

    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }

    public BigDecimal getGstPct() { return gstPct; }
    public void setGstPct(BigDecimal gstPct) { this.gstPct = gstPct; }

    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }

    public LocalDate getEffectiveFrom() { return effectiveFrom; }
    public void setEffectiveFrom(LocalDate effectiveFrom) { this.effectiveFrom = effectiveFrom; }

    public LocalDate getEffectiveTo() { return effectiveTo; }
    public void setEffectiveTo(LocalDate effectiveTo) { this.effectiveTo = effectiveTo; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}

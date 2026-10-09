package com.example.mybill.wholesale.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;

@Entity
@Table(name = "wholesale_stock_adjustment_items")
public class WholesaleStockAdjustmentItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "adjustment_item_id")
    private Integer adjustmentItemId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "adjustment_id", nullable = false)
    private WholesaleStockAdjustment adjustment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "wholesale_product_id", nullable = false)
    private WholesaleProduct product;

    /** Positive = add to stock, negative = remove. */
    @Column(name = "quantity_change", nullable = false, precision = 12, scale = 3) private BigDecimal quantityChange;
    @Column(name = "notes", length = 255) private String notes;

    public Integer getAdjustmentItemId() { return adjustmentItemId; }
    public WholesaleStockAdjustment getAdjustment() { return adjustment; }
    public void setAdjustment(WholesaleStockAdjustment adjustment) { this.adjustment = adjustment; }
    public WholesaleProduct getProduct() { return product; }
    public void setProduct(WholesaleProduct product) { this.product = product; }
    public BigDecimal getQuantityChange() { return quantityChange; }
    public void setQuantityChange(BigDecimal quantityChange) { this.quantityChange = quantityChange; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}

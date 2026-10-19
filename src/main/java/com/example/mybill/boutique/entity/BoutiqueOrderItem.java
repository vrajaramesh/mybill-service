package com.example.mybill.boutique.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;

/**
 * One line of a boutique order. Design name/code, option names/prices and the customer's measurements are
 * copied onto the line, so editing the catalog or the customer's profile later never changes this order.
 */
@Entity
@Table(name = "boutique_order_items")
public class BoutiqueOrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "item_id")
    private Integer itemId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private BoutiqueOrder order;

    @Column(name = "design_id")
    private Integer designId;

    @Column(name = "design_code", nullable = false, length = 30)
    private String designCode;

    @Column(name = "design_name", nullable = false, length = 150)
    private String designName;

    @Column(name = "size_code", nullable = false, length = 10)
    private String sizeCode;

    /** JSON array of {option_id, option_group, name, price_delta}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "options_snapshot", nullable = false, columnDefinition = "jsonb")
    private String optionsSnapshot = "[]";

    /** JSON object of the customer's measurements at order time, or null if none were recorded. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "measurements_snapshot", columnDefinition = "jsonb")
    private String measurementsSnapshot;

    @Column(name = "unit_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Column(name = "line_total", nullable = false, precision = 10, scale = 2)
    private BigDecimal lineTotal;

    public Integer getItemId() { return itemId; }
    public void setItemId(Integer itemId) { this.itemId = itemId; }
    public BoutiqueOrder getOrder() { return order; }
    public void setOrder(BoutiqueOrder order) { this.order = order; }
    public Integer getDesignId() { return designId; }
    public void setDesignId(Integer designId) { this.designId = designId; }
    public String getDesignCode() { return designCode; }
    public void setDesignCode(String designCode) { this.designCode = designCode; }
    public String getDesignName() { return designName; }
    public void setDesignName(String designName) { this.designName = designName; }
    public String getSizeCode() { return sizeCode; }
    public void setSizeCode(String sizeCode) { this.sizeCode = sizeCode; }
    public String getOptionsSnapshot() { return optionsSnapshot; }
    public void setOptionsSnapshot(String optionsSnapshot) { this.optionsSnapshot = optionsSnapshot; }
    public String getMeasurementsSnapshot() { return measurementsSnapshot; }
    public void setMeasurementsSnapshot(String measurementsSnapshot) { this.measurementsSnapshot = measurementsSnapshot; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
    public BigDecimal getLineTotal() { return lineTotal; }
    public void setLineTotal(BigDecimal lineTotal) { this.lineTotal = lineTotal; }
}

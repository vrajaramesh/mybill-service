package com.example.mybill.wholesale.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;

@Entity
@Table(name = "wholesale_purchase_items")
public class WholesalePurchaseItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "wholesale_purchase_item_id")
    private Integer wholesalePurchaseItemId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "wholesale_purchase_id", nullable = false)
    private WholesalePurchase purchase;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "wholesale_product_id", nullable = false)
    private WholesaleProduct product;

    /** HSN at the time of purchase (snapshot; the product's HSN may change later). */
    @Column(name = "hsn_code", length = 8)
    private String hsnCode;

    @Column(name = "quantity", nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    @Column(name = "purchase_rate", nullable = false, precision = 12, scale = 2)
    private BigDecimal purchaseRate;

    @Column(name = "gst_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal gstPct;

    @Column(name = "taxable_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal taxableAmount;

    @Column(name = "cgst_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal cgstAmount = BigDecimal.ZERO;

    @Column(name = "sgst_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal sgstAmount = BigDecimal.ZERO;

    @Column(name = "igst_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal igstAmount = BigDecimal.ZERO;

    @Column(name = "gst_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal gstAmount;

    @Column(name = "total_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal totalAmount;

    public Integer getWholesalePurchaseItemId() { return wholesalePurchaseItemId; }

    public WholesalePurchase getPurchase() { return purchase; }
    public void setPurchase(WholesalePurchase purchase) { this.purchase = purchase; }

    public WholesaleProduct getProduct() { return product; }
    public void setProduct(WholesaleProduct product) { this.product = product; }

    public String getHsnCode() { return hsnCode; }
    public void setHsnCode(String hsnCode) { this.hsnCode = hsnCode; }

    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }

    public BigDecimal getPurchaseRate() { return purchaseRate; }
    public void setPurchaseRate(BigDecimal purchaseRate) { this.purchaseRate = purchaseRate; }

    public BigDecimal getGstPct() { return gstPct; }
    public void setGstPct(BigDecimal gstPct) { this.gstPct = gstPct; }

    public BigDecimal getTaxableAmount() { return taxableAmount; }
    public void setTaxableAmount(BigDecimal taxableAmount) { this.taxableAmount = taxableAmount; }

    public BigDecimal getCgstAmount() { return cgstAmount; }
    public void setCgstAmount(BigDecimal cgstAmount) { this.cgstAmount = cgstAmount; }

    public BigDecimal getSgstAmount() { return sgstAmount; }
    public void setSgstAmount(BigDecimal sgstAmount) { this.sgstAmount = sgstAmount; }

    public BigDecimal getIgstAmount() { return igstAmount; }
    public void setIgstAmount(BigDecimal igstAmount) { this.igstAmount = igstAmount; }

    public BigDecimal getGstAmount() { return gstAmount; }
    public void setGstAmount(BigDecimal gstAmount) { this.gstAmount = gstAmount; }

    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }
}

package com.example.mybill.wholesale.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;

/** One quotation line. item_name / hsn / unit / rate are snapshots; priceRule only records the source slab. */
@Entity
@Table(name = "wholesale_quotation_items")
public class WholesaleQuotationItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "quotation_item_id")
    private Integer quotationItemId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quotation_id", nullable = false)
    private WholesaleQuotation quotation;

    @Column(name = "line_no", nullable = false) private Integer lineNo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "wholesale_product_id", nullable = false)
    private WholesaleProduct product;

    /** Slab the rate came from (null for manual rates or if the rule was later deleted). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "price_rule_id")
    private WholesaleProductPriceRule priceRule;

    @Column(name = "item_name", nullable = false, length = 200) private String itemName;
    @Column(name = "product_code", length = 50) private String productCode;
    @Column(name = "hsn_code", length = 8) private String hsnCode;
    @Column(name = "unit", nullable = false, length = 20) private String unit;
    @Column(name = "description", length = 500) private String description;
    @Column(name = "quantity", nullable = false, precision = 12, scale = 3) private BigDecimal quantity;
    @Column(name = "rate", nullable = false, precision = 12, scale = 2) private BigDecimal rate;
    @Column(name = "rate_source", nullable = false, length = 10) private String rateSource;
    @Column(name = "rule_rate", precision = 12, scale = 2) private BigDecimal ruleRate;
    @Column(name = "discount_pct", nullable = false, precision = 5, scale = 2) private BigDecimal discountPct = BigDecimal.ZERO;
    @Column(name = "gross_amount", nullable = false, precision = 14, scale = 2) private BigDecimal grossAmount;
    @Column(name = "discount_amount", nullable = false, precision = 14, scale = 2) private BigDecimal discountAmount;
    @Column(name = "taxable_amount", nullable = false, precision = 14, scale = 2) private BigDecimal taxableAmount;
    @Column(name = "gst_pct", nullable = false, precision = 5, scale = 2) private BigDecimal gstPct;
    @Column(name = "cgst_amount", nullable = false, precision = 14, scale = 2) private BigDecimal cgstAmount;
    @Column(name = "sgst_amount", nullable = false, precision = 14, scale = 2) private BigDecimal sgstAmount;
    @Column(name = "igst_amount", nullable = false, precision = 14, scale = 2) private BigDecimal igstAmount;
    @Column(name = "gst_amount", nullable = false, precision = 14, scale = 2) private BigDecimal gstAmount;
    @Column(name = "total_amount", nullable = false, precision = 14, scale = 2) private BigDecimal totalAmount;

    public Integer getQuotationItemId() { return quotationItemId; }
    public WholesaleQuotation getQuotation() { return quotation; }
    public void setQuotation(WholesaleQuotation quotation) { this.quotation = quotation; }
    public Integer getLineNo() { return lineNo; }
    public void setLineNo(Integer lineNo) { this.lineNo = lineNo; }
    public WholesaleProduct getProduct() { return product; }
    public void setProduct(WholesaleProduct product) { this.product = product; }
    public WholesaleProductPriceRule getPriceRule() { return priceRule; }
    public void setPriceRule(WholesaleProductPriceRule priceRule) { this.priceRule = priceRule; }
    public String getItemName() { return itemName; }
    public void setItemName(String itemName) { this.itemName = itemName; }
    public String getProductCode() { return productCode; }
    public void setProductCode(String productCode) { this.productCode = productCode; }
    public String getHsnCode() { return hsnCode; }
    public void setHsnCode(String hsnCode) { this.hsnCode = hsnCode; }
    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }
    public BigDecimal getRate() { return rate; }
    public void setRate(BigDecimal rate) { this.rate = rate; }
    public String getRateSource() { return rateSource; }
    public void setRateSource(String rateSource) { this.rateSource = rateSource; }
    public BigDecimal getRuleRate() { return ruleRate; }
    public void setRuleRate(BigDecimal ruleRate) { this.ruleRate = ruleRate; }
    public BigDecimal getDiscountPct() { return discountPct; }
    public void setDiscountPct(BigDecimal discountPct) { this.discountPct = discountPct; }
    public BigDecimal getGrossAmount() { return grossAmount; }
    public void setGrossAmount(BigDecimal grossAmount) { this.grossAmount = grossAmount; }
    public BigDecimal getDiscountAmount() { return discountAmount; }
    public void setDiscountAmount(BigDecimal discountAmount) { this.discountAmount = discountAmount; }
    public BigDecimal getTaxableAmount() { return taxableAmount; }
    public void setTaxableAmount(BigDecimal taxableAmount) { this.taxableAmount = taxableAmount; }
    public BigDecimal getGstPct() { return gstPct; }
    public void setGstPct(BigDecimal gstPct) { this.gstPct = gstPct; }
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

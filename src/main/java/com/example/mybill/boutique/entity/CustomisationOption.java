package com.example.mybill.boutique.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;

/** A selectable customisation (e.g. SLEEVE = "Full sleeve", +₹150). designId null = applies to every design. */
@Entity
@Table(name = "customisation_options")
public class CustomisationOption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "option_id")
    private Integer optionId;

    @Column(name = "design_id")
    private Integer designId;

    /** Upper-case group key: LENGTH, SLEEVE, NECK, LINING, ... At most one option per group per order item. */
    @Column(name = "option_group", nullable = false, length = 30)
    private String optionGroup;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "price_delta", nullable = false, precision = 10, scale = 2)
    private BigDecimal priceDelta = BigDecimal.ZERO;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    public CustomisationOption() {}

    public CustomisationOption(Integer optionId, Integer designId, String optionGroup, String name,
                               BigDecimal priceDelta, boolean isActive) {
        this.optionId = optionId;
        this.designId = designId;
        this.optionGroup = optionGroup;
        this.name = name;
        this.priceDelta = priceDelta;
        this.isActive = isActive;
    }

    public Integer getOptionId() { return optionId; }
    public void setOptionId(Integer optionId) { this.optionId = optionId; }
    public Integer getDesignId() { return designId; }
    public void setDesignId(Integer designId) { this.designId = designId; }
    public String getOptionGroup() { return optionGroup; }
    public void setOptionGroup(String optionGroup) { this.optionGroup = optionGroup; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public BigDecimal getPriceDelta() { return priceDelta; }
    public void setPriceDelta(BigDecimal priceDelta) { this.priceDelta = priceDelta; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
}

package com.example.mybill.boutique.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;

/** One row of the firm's standard size chart (body measurements in inches). */
@Entity
@Table(name = "size_chart")
public class SizeChartEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "size_id")
    private Integer sizeId;

    @Column(name = "size_code", nullable = false, length = 5)
    private String sizeCode;

    @Column(name = "bust_in", nullable = false, precision = 4, scale = 1)
    private BigDecimal bustIn;

    @Column(name = "waist_in", nullable = false, precision = 4, scale = 1)
    private BigDecimal waistIn;

    @Column(name = "hip_in", nullable = false, precision = 4, scale = 1)
    private BigDecimal hipIn;

    @Column(name = "shoulder_in", nullable = false, precision = 4, scale = 1)
    private BigDecimal shoulderIn;

    @Column(name = "length_in", nullable = false, precision = 4, scale = 1)
    private BigDecimal lengthIn;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    public Integer getSizeId() { return sizeId; }
    public void setSizeId(Integer sizeId) { this.sizeId = sizeId; }
    public String getSizeCode() { return sizeCode; }
    public void setSizeCode(String sizeCode) { this.sizeCode = sizeCode; }
    public BigDecimal getBustIn() { return bustIn; }
    public void setBustIn(BigDecimal bustIn) { this.bustIn = bustIn; }
    public BigDecimal getWaistIn() { return waistIn; }
    public void setWaistIn(BigDecimal waistIn) { this.waistIn = waistIn; }
    public BigDecimal getHipIn() { return hipIn; }
    public void setHipIn(BigDecimal hipIn) { this.hipIn = hipIn; }
    public BigDecimal getShoulderIn() { return shoulderIn; }
    public void setShoulderIn(BigDecimal shoulderIn) { this.shoulderIn = shoulderIn; }
    public BigDecimal getLengthIn() { return lengthIn; }
    public void setLengthIn(BigDecimal lengthIn) { this.lengthIn = lengthIn; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
}

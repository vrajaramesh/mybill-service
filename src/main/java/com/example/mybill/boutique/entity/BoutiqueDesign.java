package com.example.mybill.boutique.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Read model of the existing boutique_designs table (managed by BoutiqueDesignController / the admin UI).
 * rough_price is the design's base price and delivery_days its lead time.
 */
@Entity
@Table(name = "boutique_designs")
public class BoutiqueDesign {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "design_id")
    private Integer designId;

    @Column(name = "code", length = 30)
    private String code;

    @Column(name = "name", length = 150)
    private String name;

    @Column(name = "garment_type", nullable = false, length = 100)
    private String garmentType;

    @Column(name = "description")
    private String description;

    @Column(name = "fabric_description")
    private String fabricDescription;

    @Column(name = "rough_price", precision = 10, scale = 2)
    private BigDecimal basePrice;

    @Column(name = "delivery_days")
    private Integer leadTimeDays;

    @Column(name = "fabric_product_id")
    private Integer fabricProductId;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    /** Our tailor must take the customer's exact measurements (phone call after payment). */
    @Column(name = "exact_measurements", nullable = false)
    private Boolean exactMeasurements = false;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** Display name: the explicit name, else the garment type (older designs have no name). */
    public String displayName() {
        return name != null && !name.isBlank() ? name : garmentType;
    }

    public Integer getDesignId() { return designId; }
    public void setDesignId(Integer designId) { this.designId = designId; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getGarmentType() { return garmentType; }
    public void setGarmentType(String garmentType) { this.garmentType = garmentType; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getFabricDescription() { return fabricDescription; }
    public void setFabricDescription(String fabricDescription) { this.fabricDescription = fabricDescription; }
    public BigDecimal getBasePrice() { return basePrice; }
    public void setBasePrice(BigDecimal basePrice) { this.basePrice = basePrice; }
    public Integer getLeadTimeDays() { return leadTimeDays; }
    public void setLeadTimeDays(Integer leadTimeDays) { this.leadTimeDays = leadTimeDays; }
    public Integer getFabricProductId() { return fabricProductId; }
    public void setFabricProductId(Integer fabricProductId) { this.fabricProductId = fabricProductId; }
    public boolean needsExactMeasurements() { return Boolean.TRUE.equals(exactMeasurements); }
    public Boolean getExactMeasurements() { return exactMeasurements; }
    public void setExactMeasurements(Boolean v) { this.exactMeasurements = v; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}

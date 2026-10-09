package com.example.mybill.wholesale.entity;

import com.example.mybill.service.Supplier;
import jakarta.persistence.*;
import org.hibernate.annotations.DynamicUpdate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Wholesale product master. Deliberately separate from the retail products table and has
 * no selling price: wholesale prices are decided per sale from quantity and business rules.
 */
@Entity
@Table(name = "wholesale_products")
@DynamicUpdate
public class WholesaleProduct {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "wholesale_product_id")
    private Integer wholesaleProductId;

    @Column(name = "product_code", length = 50)
    private String productCode;

    @Column(name = "product_name", nullable = false, length = 200)
    private String productName;

    @Column(name = "product_type", length = 100)
    private String productType;

    @Column(name = "hsn_code", length = 8)
    private String hsnCode;

    @Column(name = "description")
    private String description;

    /** Fabric / material information, e.g. "100% cotton, 58 inch". */
    @Column(name = "material", length = 200)
    private String material;

    @Column(name = "unit", nullable = false, length = 20)
    private String unit;

    /** Customer-facing knowledge used by the wholesale AI assistant. Colours / designs are comma separated. */
    @Column(name = "fabric_type", length = 100)
    private String fabricType;

    @Column(name = "available_colors", length = 1000)
    private String availableColors;

    @Column(name = "available_designs", length = 1000)
    private String availableDesigns;

    /** One "Name: Value" per line, e.g. "Width: 58 inch". */
    @Column(name = "specifications")
    private String specifications;

    /** Changed only through WholesaleProductRepository's atomic stock queries, never via entity updates. */
    @Column(name = "available_quantity", nullable = false, precision = 12, scale = 3, updatable = false)
    private BigDecimal availableQuantity = BigDecimal.ZERO;

    /** Rate from the most recent wholesale purchase (or as entered when created manually). */
    @Column(name = "purchase_rate", nullable = false, precision = 12, scale = 2)
    private BigDecimal purchaseRate = BigDecimal.ZERO;

    @Column(name = "gst_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal gstPct = new BigDecimal("5");

    /** Supplier of the most recent wholesale purchase. References the shared suppliers table. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id")
    private Supplier supplier;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC, imageId ASC")
    private List<WholesaleProductImage> images = new ArrayList<>();

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Integer getWholesaleProductId() { return wholesaleProductId; }
    public void setWholesaleProductId(Integer wholesaleProductId) { this.wholesaleProductId = wholesaleProductId; }

    public String getProductCode() { return productCode; }
    public void setProductCode(String productCode) { this.productCode = productCode; }

    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }

    public String getProductType() { return productType; }
    public void setProductType(String productType) { this.productType = productType; }

    public String getHsnCode() { return hsnCode; }
    public void setHsnCode(String hsnCode) { this.hsnCode = hsnCode; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getMaterial() { return material; }
    public void setMaterial(String material) { this.material = material; }

    public String getUnit() { return unit; }

    public String getFabricType() { return fabricType; }
    public void setFabricType(String fabricType) { this.fabricType = fabricType; }
    public String getAvailableColors() { return availableColors; }
    public void setAvailableColors(String availableColors) { this.availableColors = availableColors; }
    public String getAvailableDesigns() { return availableDesigns; }
    public void setAvailableDesigns(String availableDesigns) { this.availableDesigns = availableDesigns; }
    public String getSpecifications() { return specifications; }
    public void setSpecifications(String specifications) { this.specifications = specifications; }
    public void setUnit(String unit) { this.unit = unit; }

    public BigDecimal getAvailableQuantity() { return availableQuantity; }

    public BigDecimal getPurchaseRate() { return purchaseRate; }
    public void setPurchaseRate(BigDecimal purchaseRate) { this.purchaseRate = purchaseRate; }

    public BigDecimal getGstPct() { return gstPct; }
    public void setGstPct(BigDecimal gstPct) { this.gstPct = gstPct; }

    public Supplier getSupplier() { return supplier; }
    public void setSupplier(Supplier supplier) { this.supplier = supplier; }

    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public List<WholesaleProductImage> getImages() { return images; }
}

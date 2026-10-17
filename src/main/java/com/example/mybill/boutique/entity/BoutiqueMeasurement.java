package com.example.mybill.boutique.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Agent view of the shared `customer_measurements` table: the simple size profile only
 * (the detailed tailoring fields stay with the retail CustomerMeasurement entity).
 * Insert-only: every save is a new row and the newest row is the customer's current profile.
 */
@Entity
@Table(name = "customer_measurements")
public class BoutiqueMeasurement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "measurement_id")
    private Integer measurementId;

    @Column(name = "customer_id", nullable = false)
    private Integer customerId;

    @Column(name = "profile_name", nullable = false, length = 100)
    private String profileName = "Online";

    @Column(name = "size_code", length = 5)
    private String sizeCode;

    @Column(name = "height_cm", precision = 5, scale = 1)
    private BigDecimal heightCm;

    /** Bust is stored in the existing "chest" column. */
    @Column(name = "chest", precision = 5, scale = 1)
    private BigDecimal bustIn;

    @Column(name = "waist", precision = 5, scale = 1)
    private BigDecimal waistIn;

    @Column(name = "hip", precision = 5, scale = 1)
    private BigDecimal hipIn;

    /** SNUG, REGULAR or RELAXED. */
    @Column(name = "fit_preference", length = 10)
    private String fitPreference;

    @Column(name = "notes")
    private String notes;

    @Column(name = "source", nullable = false, length = 20)
    private String source = "SHOP";

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public Integer getMeasurementId() { return measurementId; }
    public void setMeasurementId(Integer measurementId) { this.measurementId = measurementId; }
    public Integer getCustomerId() { return customerId; }
    public void setCustomerId(Integer customerId) { this.customerId = customerId; }
    public String getProfileName() { return profileName; }
    public void setProfileName(String profileName) { this.profileName = profileName; }
    public String getSizeCode() { return sizeCode; }
    public void setSizeCode(String sizeCode) { this.sizeCode = sizeCode; }
    public BigDecimal getHeightCm() { return heightCm; }
    public void setHeightCm(BigDecimal heightCm) { this.heightCm = heightCm; }
    public BigDecimal getBustIn() { return bustIn; }
    public void setBustIn(BigDecimal bustIn) { this.bustIn = bustIn; }
    public BigDecimal getWaistIn() { return waistIn; }
    public void setWaistIn(BigDecimal waistIn) { this.waistIn = waistIn; }
    public BigDecimal getHipIn() { return hipIn; }
    public void setHipIn(BigDecimal hipIn) { this.hipIn = hipIn; }
    public String getFitPreference() { return fitPreference; }
    public void setFitPreference(String fitPreference) { this.fitPreference = fitPreference; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}

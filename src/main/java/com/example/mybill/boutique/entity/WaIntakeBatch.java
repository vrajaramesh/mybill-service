package com.example.mybill.boutique.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** One supplier post forwarded by the owner to the shop's WhatsApp number (V18). */
@Entity
@Table(name = "wa_intake_batch")
public class WaIntakeBatch {

    public static final String COLLECTING = "COLLECTING", AWAITING_SUPPLIER = "AWAITING_SUPPLIER", EXTRACTING = "EXTRACTING",
        AWAITING_APPROVAL = "AWAITING_APPROVAL", APPROVED = "APPROVED", PUBLISHED = "PUBLISHED", SKIPPED = "SKIPPED",
        FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "batch_id")
    private Integer batchId;

    @Column(name = "from_number", nullable = false, length = 20) private String fromNumber;
    @Column(name = "status", nullable = false, length = 20) private String status = COLLECTING;
    @Column(name = "collection_id") private Integer collectionId;
    @Column(name = "supplier_id") private Integer supplierId;
    /** JSON the AI read from the post (name, model, prices, colours...). */
    @Column(name = "extracted") private String extracted;
    @Column(name = "error") private String error;
    @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;
    @Column(name = "closed_at") private LocalDateTime closedAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Integer getBatchId() { return batchId; }
    public String getFromNumber() { return fromNumber; }
    public void setFromNumber(String fromNumber) { this.fromNumber = fromNumber; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getCollectionId() { return collectionId; }
    public void setCollectionId(Integer collectionId) { this.collectionId = collectionId; }
    public Integer getSupplierId() { return supplierId; }
    public void setSupplierId(Integer supplierId) { this.supplierId = supplierId; }
    public String getExtracted() { return extracted; }
    public void setExtracted(String extracted) { this.extracted = extracted; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    /** Restarts the idle timer (a new message arrived); also makes the row dirty so it is saved. */
    public void setUpdatedAtNow() { this.updatedAt = LocalDateTime.now(); }
    public LocalDateTime getClosedAt() { return closedAt; }
    public void setClosedAt(LocalDateTime closedAt) { this.closedAt = closedAt; }
}

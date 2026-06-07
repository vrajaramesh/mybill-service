package com.example.mybill.service;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "product_model")
public class ProductModelConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "garment_type", nullable = false, length = 100, unique = true)
    private String garmentType;

    @Column(name = "model_name", length = 200)
    private String modelName;

    @Column(name = "model_image_url", nullable = false, columnDefinition = "TEXT")
    private String modelImageUrl;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public ProductModelConfig() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getGarmentType() { return garmentType; }
    public void setGarmentType(String garmentType) { this.garmentType = garmentType; }

    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }

    public String getModelImageUrl() { return modelImageUrl; }
    public void setModelImageUrl(String modelImageUrl) { this.modelImageUrl = modelImageUrl; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}

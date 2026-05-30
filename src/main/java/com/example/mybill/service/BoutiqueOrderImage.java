package com.example.mybill.service;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "boutique_order_images")
public class BoutiqueOrderImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "image_id")
    private Integer imageId;

    @JsonIgnore
    @ManyToOne
    @JoinColumn(name = "item_id", nullable = false)
    private StitchingOrderItem item;

    @Column(name = "image_url", nullable = false, length = 500)
    private String imageUrl;

    @Column(name = "public_id", length = 255)
    private String publicId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public BoutiqueOrderImage() {}

    public Integer getImageId() { return imageId; }
    public void setImageId(Integer v) { this.imageId = v; }

    public StitchingOrderItem getItem() { return item; }
    public void setItem(StitchingOrderItem v) { this.item = v; }

    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String v) { this.imageUrl = v; }

    public String getPublicId() { return publicId; }
    public void setPublicId(String v) { this.publicId = v; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime v) { this.createdAt = v; }
}

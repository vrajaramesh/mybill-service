package com.example.mybill.service;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "product_marketing_content")
public class ProductMarketingContent {

    @Id
    @Column(name = "product_id")
    private Integer productId;

    @Column(name = "instagram_caption", columnDefinition = "TEXT")
    private String instagramCaption;

    @Column(name = "whatsapp_text", columnDefinition = "TEXT")
    private String whatsappText;

    @Column(name = "hashtags", columnDefinition = "TEXT")
    private String hashtags;

    @Column(name = "seo_description", columnDefinition = "TEXT")
    private String seoDescription;

    @Column(name = "generated_at")
    private LocalDateTime generatedAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public ProductMarketingContent() {}

    public Integer getProductId() { return productId; }
    public void setProductId(Integer productId) { this.productId = productId; }

    public String getInstagramCaption() { return instagramCaption; }
    public void setInstagramCaption(String instagramCaption) { this.instagramCaption = instagramCaption; }

    public String getWhatsappText() { return whatsappText; }
    public void setWhatsappText(String whatsappText) { this.whatsappText = whatsappText; }

    public String getHashtags() { return hashtags; }
    public void setHashtags(String hashtags) { this.hashtags = hashtags; }

    public String getSeoDescription() { return seoDescription; }
    public void setSeoDescription(String seoDescription) { this.seoDescription = seoDescription; }

    public LocalDateTime getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(LocalDateTime generatedAt) { this.generatedAt = generatedAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}

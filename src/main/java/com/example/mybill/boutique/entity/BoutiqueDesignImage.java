package com.example.mybill.boutique.entity;

import jakarta.persistence.*;

/** Read model of boutique_design_images (uploaded through the admin UI). */
@Entity
@Table(name = "boutique_design_images")
public class BoutiqueDesignImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "design_id", nullable = false)
    private Integer designId;

    @Column(name = "image_url", nullable = false, length = 500)
    private String imageUrl;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public Integer getDesignId() { return designId; }
    public void setDesignId(Integer designId) { this.designId = designId; }
    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
}

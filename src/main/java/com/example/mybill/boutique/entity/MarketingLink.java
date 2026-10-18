package com.example.mybill.boutique.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** Maps a Click-to-WhatsApp ad (AD) or Instagram post (IG_MEDIA) id to the design it promotes. */
@Entity
@Table(name = "marketing_links")
public class MarketingLink {

    public static final String AD = "AD";
    public static final String IG_MEDIA = "IG_MEDIA";
    /** Created by staff in mybill-ui (never touched by the Instagram sync) vs by sync-ig-media. */
    public static final String MANUAL = "MANUAL";
    public static final String AUTO = "AUTO";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "link_id")
    private Integer linkId;

    @Column(name = "source_type", nullable = false, length = 20)
    private String sourceType;

    /** Meta's numeric ad id / media id, kept as text (too long for a BIGINT in some cases). */
    @Column(name = "source_id", nullable = false, length = 40)
    private String sourceId;

    @Column(name = "design_code", nullable = false, length = 30)
    private String designCode;

    @Column(name = "label", nullable = false, length = 150)
    private String label;

    /** Instagram post URL (normalised: no query, no trailing slash, no "www."), for resolve by permalink. */
    @Column(name = "permalink", length = 500)
    private String permalink;

    @Column(name = "created_by", nullable = false, length = 10)
    private String createdBy = MANUAL;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public Integer getLinkId() { return linkId; }
    public void setLinkId(Integer linkId) { this.linkId = linkId; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public String getSourceId() { return sourceId; }
    public void setSourceId(String sourceId) { this.sourceId = sourceId; }
    public String getDesignCode() { return designCode; }
    public void setDesignCode(String designCode) { this.designCode = designCode; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getPermalink() { return permalink; }
    public void setPermalink(String permalink) { this.permalink = permalink; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}

package com.example.mybill.wholesale.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** Instagram messaging connection + AI reply rules (single row). Token and app secret are never returned by the API. */
@Entity
@Table(name = "wholesale_instagram_settings")
public class WholesaleInstagramSettings {

    public static final short SINGLETON_ID = 1;

    @Id
    @Column(name = "settings_id")
    private Short settingsId = SINGLETON_ID;

    @Column(name = "is_enabled", nullable = false) private Boolean isEnabled = false;
    @Column(name = "auto_reply_enabled", nullable = false) private Boolean autoReplyEnabled = true;
    @Column(name = "instagram_account_id", length = 64) private String instagramAccountId;
    @Column(name = "api_base_url", nullable = false, length = 255) private String apiBaseUrl = "https://graph.facebook.com/v21.0";
    @Column(name = "access_token") private String accessToken;
    @Column(name = "app_secret", length = 255) private String appSecret;
    @Column(name = "verify_token", length = 255) private String verifyToken;
    @Column(name = "handoff_message", nullable = false, length = 500)
    private String handoffMessage = "Thank you for your message. Our team will get back to you shortly.";
    @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;
    @Column(name = "updated_by", length = 100) private String updatedBy;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = LocalDateTime.now();
    }

    public Boolean getIsEnabled() { return isEnabled; }
    public void setIsEnabled(Boolean isEnabled) { this.isEnabled = isEnabled; }
    public Boolean getAutoReplyEnabled() { return autoReplyEnabled; }
    public void setAutoReplyEnabled(Boolean autoReplyEnabled) { this.autoReplyEnabled = autoReplyEnabled; }
    public String getInstagramAccountId() { return instagramAccountId; }
    public void setInstagramAccountId(String instagramAccountId) { this.instagramAccountId = instagramAccountId; }
    public String getApiBaseUrl() { return apiBaseUrl; }
    public void setApiBaseUrl(String apiBaseUrl) { this.apiBaseUrl = apiBaseUrl; }
    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
    public String getAppSecret() { return appSecret; }
    public void setAppSecret(String appSecret) { this.appSecret = appSecret; }
    public String getVerifyToken() { return verifyToken; }
    public void setVerifyToken(String verifyToken) { this.verifyToken = verifyToken; }
    public String getHandoffMessage() { return handoffMessage; }
    public void setHandoffMessage(String handoffMessage) { this.handoffMessage = handoffMessage; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
}

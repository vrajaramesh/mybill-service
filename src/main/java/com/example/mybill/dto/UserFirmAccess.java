package com.example.mybill.dto;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "user_firm_access", schema = "public")
public class UserFirmAccess {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "access_id")
    private Integer accessId;

    @Column(name = "user_id", nullable = false)
    private Integer userId;  // Not a direct FK due to dynamic schema reference

    @Column(name = "firm_id", nullable = false)
    private Integer firmId;

    @ManyToOne
    @JoinColumn(name = "firm_id", insertable = false, updatable = false)
    private Firm firm;

    @Column(name = "firm_code", nullable = false, length = 50)
    private String firmCode;

    @Column(name = "access_level", nullable = false, length = 20)
    private String accessLevel = "ADMIN";  // ADMIN, MANAGER, VIEWER

    @Column(name = "is_primary", nullable = false)
    private Boolean isPrimary = false;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "assigned_by", length = 100)
    private String assignedBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public UserFirmAccess() {}

    public Integer getAccessId() { return accessId; }
    public void setAccessId(Integer accessId) { this.accessId = accessId; }

    public Integer getUserId() { return userId; }
    public void setUserId(Integer userId) { this.userId = userId; }

    public Integer getFirmId() { return firmId; }
    public void setFirmId(Integer firmId) { this.firmId = firmId; }

    public Firm getFirm() { return firm; }
    public void setFirm(Firm firm) { this.firm = firm; }

    public String getFirmCode() { return firmCode; }
    public void setFirmCode(String firmCode) { this.firmCode = firmCode; }

    public String getAccessLevel() { return accessLevel; }
    public void setAccessLevel(String accessLevel) { this.accessLevel = accessLevel; }

    public Boolean getIsPrimary() { return isPrimary; }
    public void setIsPrimary(Boolean isPrimary) { this.isPrimary = isPrimary; }

    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }

    public String getAssignedBy() { return assignedBy; }
    public void setAssignedBy(String assignedBy) { this.assignedBy = assignedBy; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}


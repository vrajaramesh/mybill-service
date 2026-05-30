package com.example.mybill.dto;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "firms", schema = "public")
public class Firm {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "firm_id")
    private Long firmId;

    @Column(name = "firm_name", nullable = false, length = 200)
    private String firmName;

    @Column(name = "firm_code", nullable = false, unique = true, length = 50)
    private String firmCode;

    @Column(name = "schema_name", nullable = false, unique = true, length = 100)
    private String schemaName;

    @Column(name = "owner_email", length = 100)
    private String ownerEmail;

    @Column(name = "is_active")
    private Boolean isActive = true;

    @Column(name = "superadmin_id")
    private Integer superadminId;

    @ManyToOne
    @JoinColumn(name = "superadmin_id", insertable = false, updatable = false)
    private AppUserPublic superadmin;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public Firm() {}

    public Long getFirmId() { return firmId; }
    public void setFirmId(Long firmId) { this.firmId = firmId; }

    public String getFirmName() { return firmName; }
    public void setFirmName(String firmName) { this.firmName = firmName; }

    public String getFirmCode() { return firmCode; }
    public void setFirmCode(String firmCode) { this.firmCode = firmCode; }

    public String getSchemaName() { return schemaName; }
    public void setSchemaName(String schemaName) { this.schemaName = schemaName; }

    public String getOwnerEmail() { return ownerEmail; }
    public void setOwnerEmail(String ownerEmail) { this.ownerEmail = ownerEmail; }

    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }

    public Integer getSuperadminId() { return superadminId; }
    public void setSuperadminId(Integer superadminId) { this.superadminId = superadminId; }

    public AppUserPublic getSuperadmin() { return superadmin; }
    public void setSuperadmin(AppUserPublic superadmin) { this.superadmin = superadmin; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}

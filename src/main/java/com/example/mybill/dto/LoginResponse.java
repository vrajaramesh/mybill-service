package com.example.mybill.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class LoginResponse {

    @JsonProperty("token")
    private String token;

    @JsonProperty("userId")
    private Integer userId;

    @JsonProperty("username")
    private String username;

    @JsonProperty("fullName")
    private String fullName;

    @JsonProperty("email")
    private String email;

    @JsonProperty("phone")
    private String phone;

    @JsonProperty("role")
    private String role;

    @JsonProperty("isSuperadmin")
    private Boolean isSuperadmin = false;

    @JsonProperty("firmId")
    private Integer firmId;

    @JsonProperty("firmName")
    private String firmName;

    @JsonProperty("firmCode")
    private String firmCode;

    @JsonProperty("accessLevel")
    private String accessLevel;

    @JsonProperty("availableFirms")
    private List<FirmAccessInfo> availableFirms;

    @JsonProperty("requiresFirmSelection")
    private Boolean requiresFirmSelection = false;

    public LoginResponse() {}

    // Getters and Setters
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public Integer getUserId() { return userId; }
    public void setUserId(Integer userId) { this.userId = userId; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public Boolean getIsSuperadmin() { return isSuperadmin; }
    public void setIsSuperadmin(Boolean isSuperadmin) { this.isSuperadmin = isSuperadmin; }

    public Integer getFirmId() { return firmId; }
    public void setFirmId(Integer firmId) { this.firmId = firmId; }

    public String getFirmName() { return firmName; }
    public void setFirmName(String firmName) { this.firmName = firmName; }

    public String getFirmCode() { return firmCode; }
    public void setFirmCode(String firmCode) { this.firmCode = firmCode; }

    public String getAccessLevel() { return accessLevel; }
    public void setAccessLevel(String accessLevel) { this.accessLevel = accessLevel; }

    public List<FirmAccessInfo> getAvailableFirms() { return availableFirms; }
    public void setAvailableFirms(List<FirmAccessInfo> availableFirms) { this.availableFirms = availableFirms; }

    public Boolean getRequiresFirmSelection() { return requiresFirmSelection; }
    public void setRequiresFirmSelection(Boolean requiresFirmSelection) { this.requiresFirmSelection = requiresFirmSelection; }

    /**
     * Inner class representing firm access information
     */
    public static class FirmAccessInfo {
        @JsonProperty("firmId")
        private Integer firmId;

        @JsonProperty("firmName")
        private String firmName;

        @JsonProperty("firmCode")
        private String firmCode;

        @JsonProperty("accessLevel")
        private String accessLevel;

        @JsonProperty("isPrimary")
        private Boolean isPrimary;

        public FirmAccessInfo() {}

        public FirmAccessInfo(Integer firmId, String firmName, String firmCode, String accessLevel, Boolean isPrimary) {
            this.firmId = firmId;
            this.firmName = firmName;
            this.firmCode = firmCode;
            this.accessLevel = accessLevel;
            this.isPrimary = isPrimary;
        }

        public Integer getFirmId() { return firmId; }
        public void setFirmId(Integer firmId) { this.firmId = firmId; }

        public String getFirmName() { return firmName; }
        public void setFirmName(String firmName) { this.firmName = firmName; }

        public String getFirmCode() { return firmCode; }
        public void setFirmCode(String firmCode) { this.firmCode = firmCode; }

        public String getAccessLevel() { return accessLevel; }
        public void setAccessLevel(String accessLevel) { this.accessLevel = accessLevel; }

        public Boolean getIsPrimary() { return isPrimary; }
        public void setIsPrimary(Boolean isPrimary) { this.isPrimary = isPrimary; }
    }
}


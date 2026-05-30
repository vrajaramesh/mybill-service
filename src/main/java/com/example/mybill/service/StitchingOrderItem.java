package com.example.mybill.service;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "stitching_order_items")
public class StitchingOrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "item_id")
    private Integer itemId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    @JsonIgnore
    private StitchingOrder order;

    @Column(name = "garment_type", nullable = false, length = 50)
    private String garmentType;

    @Column(name = "fabric_description", length = 200)
    private String fabricDescription;

    @Column(name = "quantity")
    private Integer quantity = 1;

    @Column(name = "stitching_charges", precision = 10, scale = 2)
    private BigDecimal stitchingCharges = BigDecimal.ZERO;

    @Column(name = "special_instructions", length = 500)
    private String specialInstructions;

    @Column(name = "item_status", length = 30)
    private String itemStatus = "PENDING";

    // ── Generic measurements (all garments, in inches) ────────────────────
    @Column(name = "chest",            precision = 5, scale = 1) private BigDecimal chest;
    @Column(name = "waist",            precision = 5, scale = 1) private BigDecimal waist;
    @Column(name = "hip",              precision = 5, scale = 1) private BigDecimal hip;
    @Column(name = "shoulder",         precision = 5, scale = 1) private BigDecimal shoulder;
    @Column(name = "sleeve_length",    precision = 5, scale = 1) private BigDecimal sleeveLength;
    @Column(name = "blouse_length",    precision = 5, scale = 1) private BigDecimal blouseLength;
    @Column(name = "neck_front_depth", precision = 5, scale = 1) private BigDecimal neckFrontDepth;
    @Column(name = "neck_back_depth",  precision = 5, scale = 1) private BigDecimal neckBackDepth;
    @Column(name = "neck_width",       precision = 5, scale = 1) private BigDecimal neckWidth;
    @Column(name = "kurta_length",     precision = 5, scale = 1) private BigDecimal kurtaLength;
    @Column(name = "salwar_length",    precision = 5, scale = 1) private BigDecimal salwarLength;
    @Column(name = "full_length",      precision = 5, scale = 1) private BigDecimal fullLength;
    @Column(name = "armhole",          precision = 5, scale = 1) private BigDecimal armhole;

    // ── Blouse-specific measurements ─────────────────────────────────────
    // Circumference
    @Column(name = "upper_bust",             precision = 5, scale = 1) private BigDecimal upperBust;
    @Column(name = "under_bust",             precision = 5, scale = 1) private BigDecimal underBust;
    // Sleeve
    @Column(name = "sleeve_round",           precision = 5, scale = 1) private BigDecimal sleeveRound;
    @Column(name = "bicep_round",            precision = 5, scale = 1) private BigDecimal bicepRound;
    @Column(name = "elbow_round",            precision = 5, scale = 1) private BigDecimal elbowRound;
    @Column(name = "wrist_round",            precision = 5, scale = 1) private BigDecimal wristRound;
    // Fitting points
    @Column(name = "apex_point",             precision = 5, scale = 1) private BigDecimal apexPoint;
    @Column(name = "apex_to_apex",           precision = 5, scale = 1) private BigDecimal apexToApex;
    @Column(name = "shoulder_to_apex",       precision = 5, scale = 1) private BigDecimal shoulderToApex;
    @Column(name = "shoulder_to_under_bust", precision = 5, scale = 1) private BigDecimal shoulderToUnderBust;
    // Lengths
    @Column(name = "front_length",           precision = 5, scale = 1) private BigDecimal frontLength;
    @Column(name = "back_length",            precision = 5, scale = 1) private BigDecimal backLength;
    // Advanced
    @Column(name = "front_width",            precision = 5, scale = 1) private BigDecimal frontWidth;
    @Column(name = "back_width",             precision = 5, scale = 1) private BigDecimal backWidth;
    @Column(name = "side_seam_length",       precision = 5, scale = 1) private BigDecimal sideSeamLength;
    @Column(name = "strap_width",            precision = 5, scale = 1) private BigDecimal strapWidth;
    @Column(name = "princess_line_length",   precision = 5, scale = 1) private BigDecimal princessLineLength;
    // Observations (text)
    @Column(name = "cup_size_padding",       length = 100) private String cupSizePadding;
    @Column(name = "bust_shape_observation", length = 300) private String bustShapeObservation;

    // ── Skirt-specific measurements ───────────────────────────────────────
    // Circumferences
    @Column(name = "high_waist_round",    precision = 5, scale = 1) private BigDecimal highWaistRound;
    @Column(name = "low_waist_round",     precision = 5, scale = 1) private BigDecimal lowWaistRound;
    @Column(name = "seat_round",          precision = 5, scale = 1) private BigDecimal seatRound;
    @Column(name = "thigh_round",         precision = 5, scale = 1) private BigDecimal thighRound;
    @Column(name = "knee_round",          precision = 5, scale = 1) private BigDecimal kneeRound;
    @Column(name = "calf_round",          precision = 5, scale = 1) private BigDecimal calfRound;
    @Column(name = "bottom_round",        precision = 5, scale = 1) private BigDecimal bottomRound;
    // Lengths
    @Column(name = "waist_to_hip_length", precision = 5, scale = 1) private BigDecimal waistToHipLength;
    @Column(name = "waist_to_knee",       precision = 5, scale = 1) private BigDecimal waistToKnee;
    @Column(name = "slit_height",         precision = 5, scale = 1) private BigDecimal slitHeight;
    // Style notes (shared: Skirt & Frock)
    @Column(name = "can_can_requirement", length = 200) private String canCanRequirement;
    @Column(name = "waist_finish",        length = 100) private String waistFinish;
    // Frock-specific
    @Column(name = "waist_gather",        length = 200) private String waistGather;

    // ── Salwar-specific measurements ─────────────────────────────────────────
    @Column(name = "ankle_round",          precision = 5, scale = 1) private BigDecimal ankleRound;
    @Column(name = "inseam_length",        precision = 5, scale = 1) private BigDecimal inseamLength;
    @Column(name = "rise_length",          precision = 5, scale = 1) private BigDecimal riseLength;
    @Column(name = "crotch_depth",         precision = 5, scale = 1) private BigDecimal crotchDepth;
    // ── Kurta/Kurti-specific measurements ────────────────────────────────────
    @Column(name = "collar_round",         precision = 5, scale = 1) private BigDecimal collarRound;
    @Column(name = "front_slit_length",    precision = 5, scale = 1) private BigDecimal frontSlitLength;
    @Column(name = "kali_width",           precision = 5, scale = 1) private BigDecimal kaliWidth;
    // ── Lehenga-specific measurements ────────────────────────────────────────
    @Column(name = "number_of_kalis",      length = 50)              private String numberOfKalis;
    @Column(name = "trail_length",         precision = 5, scale = 1) private BigDecimal trailLength;
    // ── Choli-specific measurements ───────────────────────────────────────────
    @Column(name = "under_bust_belt_length", precision = 5, scale = 1) private BigDecimal underBustBeltLength;
    @Column(name = "back_opening_length",    precision = 5, scale = 1) private BigDecimal backOpeningLength;
    // ── Anarkali-specific measurements ───────────────────────────────────────
    @Column(name = "waist_joint_length",     precision = 5, scale = 1) private BigDecimal waistJointLength;
    @Column(name = "kali_length",            precision = 5, scale = 1) private BigDecimal kaliLength;
    // ── Trouser-specific measurements ────────────────────────────────────────
    @Column(name = "mid_thigh_round",        precision = 5, scale = 1) private BigDecimal midThighRound;
    @Column(name = "front_rise",             precision = 5, scale = 1) private BigDecimal frontRise;
    @Column(name = "back_rise",              precision = 5, scale = 1) private BigDecimal backRise;
    @Column(name = "waistband_width",        precision = 5, scale = 1) private BigDecimal waistbandWidth;
    // ── Advanced observations ─────────────────────────────────────────────────
    @Column(name = "boutique_observations", length = 500)            private String boutiqueObservations;

    // ── Getters & Setters ──────────────────────────────────────────────────
    public Integer getItemId() { return itemId; }
    public void setItemId(Integer v) { this.itemId = v; }
    public StitchingOrder getOrder() { return order; }
    public void setOrder(StitchingOrder v) { this.order = v; }
    public String getGarmentType() { return garmentType; }
    public void setGarmentType(String v) { this.garmentType = v; }
    public String getFabricDescription() { return fabricDescription; }
    public void setFabricDescription(String v) { this.fabricDescription = v; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer v) { this.quantity = v; }
    public BigDecimal getStitchingCharges() { return stitchingCharges; }
    public void setStitchingCharges(BigDecimal v) { this.stitchingCharges = v; }
    public String getSpecialInstructions() { return specialInstructions; }
    public void setSpecialInstructions(String v) { this.specialInstructions = v; }
    public String getItemStatus() { return itemStatus; }
    public void setItemStatus(String v) { this.itemStatus = v; }

    // Generic
    public BigDecimal getChest() { return chest; }
    public void setChest(BigDecimal v) { this.chest = v; }
    public BigDecimal getWaist() { return waist; }
    public void setWaist(BigDecimal v) { this.waist = v; }
    public BigDecimal getHip() { return hip; }
    public void setHip(BigDecimal v) { this.hip = v; }
    public BigDecimal getShoulder() { return shoulder; }
    public void setShoulder(BigDecimal v) { this.shoulder = v; }
    public BigDecimal getSleeveLength() { return sleeveLength; }
    public void setSleeveLength(BigDecimal v) { this.sleeveLength = v; }
    public BigDecimal getBlouseLength() { return blouseLength; }
    public void setBlouseLength(BigDecimal v) { this.blouseLength = v; }
    public BigDecimal getNeckFrontDepth() { return neckFrontDepth; }
    public void setNeckFrontDepth(BigDecimal v) { this.neckFrontDepth = v; }
    public BigDecimal getNeckBackDepth() { return neckBackDepth; }
    public void setNeckBackDepth(BigDecimal v) { this.neckBackDepth = v; }
    public BigDecimal getNeckWidth() { return neckWidth; }
    public void setNeckWidth(BigDecimal v) { this.neckWidth = v; }
    public BigDecimal getKurtaLength() { return kurtaLength; }
    public void setKurtaLength(BigDecimal v) { this.kurtaLength = v; }
    public BigDecimal getSalwarLength() { return salwarLength; }
    public void setSalwarLength(BigDecimal v) { this.salwarLength = v; }
    public BigDecimal getFullLength() { return fullLength; }
    public void setFullLength(BigDecimal v) { this.fullLength = v; }
    public BigDecimal getArmhole() { return armhole; }
    public void setArmhole(BigDecimal v) { this.armhole = v; }

    // Blouse-specific
    public BigDecimal getUpperBust() { return upperBust; }
    public void setUpperBust(BigDecimal v) { this.upperBust = v; }
    public BigDecimal getUnderBust() { return underBust; }
    public void setUnderBust(BigDecimal v) { this.underBust = v; }
    public BigDecimal getSleeveRound() { return sleeveRound; }
    public void setSleeveRound(BigDecimal v) { this.sleeveRound = v; }
    public BigDecimal getBicepRound() { return bicepRound; }
    public void setBicepRound(BigDecimal v) { this.bicepRound = v; }
    public BigDecimal getElbowRound() { return elbowRound; }
    public void setElbowRound(BigDecimal v) { this.elbowRound = v; }
    public BigDecimal getWristRound() { return wristRound; }
    public void setWristRound(BigDecimal v) { this.wristRound = v; }
    public BigDecimal getApexPoint() { return apexPoint; }
    public void setApexPoint(BigDecimal v) { this.apexPoint = v; }
    public BigDecimal getApexToApex() { return apexToApex; }
    public void setApexToApex(BigDecimal v) { this.apexToApex = v; }
    public BigDecimal getShoulderToApex() { return shoulderToApex; }
    public void setShoulderToApex(BigDecimal v) { this.shoulderToApex = v; }
    public BigDecimal getShoulderToUnderBust() { return shoulderToUnderBust; }
    public void setShoulderToUnderBust(BigDecimal v) { this.shoulderToUnderBust = v; }
    public BigDecimal getFrontLength() { return frontLength; }
    public void setFrontLength(BigDecimal v) { this.frontLength = v; }
    public BigDecimal getBackLength() { return backLength; }
    public void setBackLength(BigDecimal v) { this.backLength = v; }
    public BigDecimal getFrontWidth() { return frontWidth; }
    public void setFrontWidth(BigDecimal v) { this.frontWidth = v; }
    public BigDecimal getBackWidth() { return backWidth; }
    public void setBackWidth(BigDecimal v) { this.backWidth = v; }
    public BigDecimal getSideSeamLength() { return sideSeamLength; }
    public void setSideSeamLength(BigDecimal v) { this.sideSeamLength = v; }
    public BigDecimal getStrapWidth() { return strapWidth; }
    public void setStrapWidth(BigDecimal v) { this.strapWidth = v; }
    public BigDecimal getPrincessLineLength() { return princessLineLength; }
    public void setPrincessLineLength(BigDecimal v) { this.princessLineLength = v; }
    public String getCupSizePadding() { return cupSizePadding; }
    public void setCupSizePadding(String v) { this.cupSizePadding = v; }
    public String getBustShapeObservation() { return bustShapeObservation; }
    public void setBustShapeObservation(String v) { this.bustShapeObservation = v; }

    // Skirt
    public BigDecimal getHighWaistRound() { return highWaistRound; }
    public void setHighWaistRound(BigDecimal v) { this.highWaistRound = v; }
    public BigDecimal getLowWaistRound() { return lowWaistRound; }
    public void setLowWaistRound(BigDecimal v) { this.lowWaistRound = v; }
    public BigDecimal getSeatRound() { return seatRound; }
    public void setSeatRound(BigDecimal v) { this.seatRound = v; }
    public BigDecimal getThighRound() { return thighRound; }
    public void setThighRound(BigDecimal v) { this.thighRound = v; }
    public BigDecimal getKneeRound() { return kneeRound; }
    public void setKneeRound(BigDecimal v) { this.kneeRound = v; }
    public BigDecimal getCalfRound() { return calfRound; }
    public void setCalfRound(BigDecimal v) { this.calfRound = v; }
    public BigDecimal getBottomRound() { return bottomRound; }
    public void setBottomRound(BigDecimal v) { this.bottomRound = v; }
    public BigDecimal getWaistToHipLength() { return waistToHipLength; }
    public void setWaistToHipLength(BigDecimal v) { this.waistToHipLength = v; }
    public BigDecimal getWaistToKnee() { return waistToKnee; }
    public void setWaistToKnee(BigDecimal v) { this.waistToKnee = v; }
    public BigDecimal getSlitHeight() { return slitHeight; }
    public void setSlitHeight(BigDecimal v) { this.slitHeight = v; }
    public String getCanCanRequirement() { return canCanRequirement; }
    public void setCanCanRequirement(String v) { this.canCanRequirement = v; }
    public String getWaistFinish() { return waistFinish; }
    public void setWaistFinish(String v) { this.waistFinish = v; }
    public String getWaistGather() { return waistGather; }
    public void setWaistGather(String v) { this.waistGather = v; }

    // Salwar-specific
    public BigDecimal getAnkleRound() { return ankleRound; }
    public void setAnkleRound(BigDecimal v) { this.ankleRound = v; }
    public BigDecimal getInseamLength() { return inseamLength; }
    public void setInseamLength(BigDecimal v) { this.inseamLength = v; }
    public BigDecimal getRiseLength() { return riseLength; }
    public void setRiseLength(BigDecimal v) { this.riseLength = v; }
    public BigDecimal getCrotchDepth() { return crotchDepth; }
    public void setCrotchDepth(BigDecimal v) { this.crotchDepth = v; }

    // Kurta/Kurti-specific
    public BigDecimal getCollarRound() { return collarRound; }
    public void setCollarRound(BigDecimal v) { this.collarRound = v; }
    public BigDecimal getFrontSlitLength() { return frontSlitLength; }
    public void setFrontSlitLength(BigDecimal v) { this.frontSlitLength = v; }
    public BigDecimal getKaliWidth() { return kaliWidth; }
    public void setKaliWidth(BigDecimal v) { this.kaliWidth = v; }

    // Lehenga-specific
    public String getNumberOfKalis() { return numberOfKalis; }
    public void setNumberOfKalis(String v) { this.numberOfKalis = v; }
    public BigDecimal getTrailLength() { return trailLength; }
    public void setTrailLength(BigDecimal v) { this.trailLength = v; }

    // Choli-specific
    public BigDecimal getUnderBustBeltLength() { return underBustBeltLength; }
    public void setUnderBustBeltLength(BigDecimal v) { this.underBustBeltLength = v; }
    public BigDecimal getBackOpeningLength() { return backOpeningLength; }
    public void setBackOpeningLength(BigDecimal v) { this.backOpeningLength = v; }
    // Anarkali-specific
    public BigDecimal getWaistJointLength() { return waistJointLength; }
    public void setWaistJointLength(BigDecimal v) { this.waistJointLength = v; }
    public BigDecimal getKaliLength() { return kaliLength; }
    public void setKaliLength(BigDecimal v) { this.kaliLength = v; }
    // Trouser-specific
    public BigDecimal getMidThighRound() { return midThighRound; }
    public void setMidThighRound(BigDecimal v) { this.midThighRound = v; }
    public BigDecimal getFrontRise() { return frontRise; }
    public void setFrontRise(BigDecimal v) { this.frontRise = v; }
    public BigDecimal getBackRise() { return backRise; }
    public void setBackRise(BigDecimal v) { this.backRise = v; }
    public BigDecimal getWaistbandWidth() { return waistbandWidth; }
    public void setWaistbandWidth(BigDecimal v) { this.waistbandWidth = v; }
    // Advanced observations
    public String getBoutiqueObservations() { return boutiqueObservations; }
    public void setBoutiqueObservations(String v) { this.boutiqueObservations = v; }
}

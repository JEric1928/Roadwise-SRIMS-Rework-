package com.roadwise.backend.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "road_reports")
public class RoadReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Optimistic locking for concurrent workflow transitions (validate/dipatch/close).
    // Backed by the road_reports.version column added in V6.
    @Version
    private Long version;

    // --- 1. Road Details (From Frontend) ---
    private String cityRoadName;

    // Denormalized snapshot of the linked CityRoad's human-readable ID
    // (e.g. "3142000000 01"). The authoritative FK lives in the city_road_id column.
    @Column(name = "city_road_code")
    private String cityRoadId;

    // Denormalized snapshots of the linked CityRoad attributes, captured at
    // submission time so reports stay stable even if the road record changes.
    private String roadImportance;
    private String roadType;
    private String terrainType;
    private Double width;
    private Double length;

    @Column(name = "number_of_bridges")
    private Integer numberOfBridges;

    @Column(name = "length_of_culverts")
    private Double lengthOfCulverts;

    // --- 2. Damage Information (From Frontend) ---
    @Column(columnDefinition = "TEXT")
    private String damageImage;

    @Column(columnDefinition = "TEXT")
    private String damageDescription;
    private Double latitude;
    private Double longitude;

    private String damageType;
    private Double damageLength;
    private Double damageWidth;

    // --- 3. System & Analytics Tracking ---
    private Integer inventoryYear;
    private String status;

    // Admin & CEO Remarks
    @Column(columnDefinition = "TEXT")
    private String adminRemarks;

    @Column(columnDefinition = "TEXT")
    private String repairRemarks;

    @Column(columnDefinition = "TEXT")
    private String proofOfRepairImage;

    // 🚀 OFFICIAL DATE CONCLUDED / ARCHIVED
    @Column(name = "date_archived")
    private LocalDateTime dateArchived;

    // 🎯 TIMELINE ESTIMATION & COMPLETION TRACKING
    @Column(name = "target_completion_date")
    private LocalDate targetCompletionDate;

    // Aligned with the database: completion is tracked at day granularity.
    @Column(name = "actual_completion_date")
    private LocalDate actualCompletionDate;

    // AI Classification
    @Column(name = "cv_damage_classification")
    private String cvDamageClassification;

    @Column(name = "cv_confidence_score")
    private Double cvConfidenceScore;

    // Authoritative FK to the registered city road (the human-readable snapshot
    // code lives in city_road_code above). Writable so submissions persist the
    // real road relationship while keeping the snapshot columns for display.
    @Column(name = "city_road_id")
    private Long cityRoadFkId;

    // Duplicate-detection outcome. Persisted so the warning survives restarts and
    // can be reviewed later instead of existing only in a notification.
    @Column(name = "potential_duplicate", nullable = false)
    private Boolean potentialDuplicate = false;

    @Column(name = "duplicate_of_report_id")
    private Long duplicateOfReportId;

    // Relationships
    @JsonIgnoreProperties({"reports", "hibernateLazyInitializer", "handler"})
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "barangay_id")
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private Barangay barangay;

    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler", "password"})
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private User user;

    private String reportedBy;
    private String severity;

    // 🚀 AUTOMATIC SUBMISSION DATE & INVENTORY YEAR
    @Column(name = "date_submitted")
    private LocalDate dateSubmitted;

    @PrePersist
    public void prePersist() {
        if (this.dateSubmitted == null) {
            this.dateSubmitted = LocalDate.now();
        }
        if (this.inventoryYear == null) {
            this.inventoryYear = LocalDate.now().getYear();
        }
    }

    // 🔔 ANTI-SPAM RATE-LIMITING FOR DEADLINE REMINDERS
    @Column(name = "last_reminder_sent")
    private LocalDateTime lastReminderSent;

    // ⏱️ FORMAL CIVIL WORKS TIME EXTENSION REQUEST FIELDS
    @Column(name = "extension_target_date")
    private LocalDate extensionTargetDate;

    @Column(name = "extension_reason", length = 1000)
    private String extensionReason;

    // Status: null, "PENDING", "APPROVED", "REJECTED"
    @Column(name = "extension_status")
    private String extensionStatus;

    public RoadReport() {
    }

    // ==========================================
    // GETTERS & SETTERS
    // ==========================================
    public Long getId() {
        return id;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCityRoadName() {
        return cityRoadName;
    }

    public void setCityRoadName(String cityRoadName) {
        this.cityRoadName = cityRoadName;
    }

    public String getCityRoadId() {
        return cityRoadId;
    }

    public void setCityRoadId(String cityRoadId) {
        this.cityRoadId = cityRoadId;
    }

    public Long getCityRoadFkId() {
        return cityRoadFkId;
    }

    public void setCityRoadFkId(Long cityRoadFkId) {
        this.cityRoadFkId = cityRoadFkId;
    }

    public Boolean getPotentialDuplicate() {
        return potentialDuplicate;
    }

    public void setPotentialDuplicate(Boolean potentialDuplicate) {
        this.potentialDuplicate = potentialDuplicate;
    }

    public Long getDuplicateOfReportId() {
        return duplicateOfReportId;
    }

    public void setDuplicateOfReportId(Long duplicateOfReportId) {
        this.duplicateOfReportId = duplicateOfReportId;
    }

    public String getRoadImportance() {
        return roadImportance;
    }

    public void setRoadImportance(String roadImportance) {
        this.roadImportance = roadImportance;
    }

    public String getRoadType() {
        return roadType;
    }

    public void setRoadType(String roadType) {
        this.roadType = roadType;
    }

    public String getTerrainType() {
        return terrainType;
    }

    public void setTerrainType(String terrainType) {
        this.terrainType = terrainType;
    }

    public Double getWidth() {
        return width;
    }

    public void setWidth(Double width) {
        this.width = width;
    }

    public Double getLength() {
        return length;
    }

    public void setLength(Double length) {
        this.length = length;
    }

    public Integer getNumberOfBridges() {
        return numberOfBridges;
    }

    public void setNumberOfBridges(Integer numberOfBridges) {
        this.numberOfBridges = numberOfBridges;
    }

    public Double getLengthOfCulverts() {
        return lengthOfCulverts;
    }

    public void setLengthOfCulverts(Double lengthOfCulverts) {
        this.lengthOfCulverts = lengthOfCulverts;
    }

    public String getDamageImage() {
        return damageImage;
    }

    public void setDamageImage(String damageImage) {
        this.damageImage = damageImage;
    }

    public String getDamageDescription() {
        return damageDescription;
    }

    public void setDamageDescription(String damageDescription) {
        this.damageDescription = damageDescription;
    }

    public Double getLatitude() {
        return latitude;
    }

    public void setLatitude(Double latitude) {
        this.latitude = latitude;
    }

    public Double getLongitude() {
        return longitude;
    }

    public void setLongitude(Double longitude) {
        this.longitude = longitude;
    }

    public Integer getInventoryYear() {
        return inventoryYear;
    }

    public void setInventoryYear(Integer inventoryYear) {
        this.inventoryYear = inventoryYear;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCvDamageClassification() {
        return cvDamageClassification;
    }

    public void setCvDamageClassification(String cvDamageClassification) {
        this.cvDamageClassification = cvDamageClassification;
    }

    public Double getCvConfidenceScore() {
        return cvConfidenceScore;
    }

    public void setCvConfidenceScore(Double cvConfidenceScore) {
        this.cvConfidenceScore = cvConfidenceScore;
    }

    public String getRepairRemarks() {
        return repairRemarks;
    }

    public void setRepairRemarks(String repairRemarks) {
        this.repairRemarks = repairRemarks;
    }

    public String getProofOfRepairImage() {
        return proofOfRepairImage;
    }

    public void setProofOfRepairImage(String proofOfRepairImage) {
        this.proofOfRepairImage = proofOfRepairImage;
    }

    public Barangay getBarangay() {
        return barangay;
    }

    public LocalDateTime getLastReminderSent() {
        return lastReminderSent;
    }

    public void setLastReminderSent(LocalDateTime lastReminderSent) {
        this.lastReminderSent = lastReminderSent;
    }

    public LocalDate getExtensionTargetDate() {
        return extensionTargetDate;
    }

    public void setExtensionTargetDate(LocalDate extensionTargetDate) {
        this.extensionTargetDate = extensionTargetDate;
    }

    public String getExtensionReason() {
        return extensionReason;
    }

    public void setExtensionReason(String extensionReason) {
        this.extensionReason = extensionReason;
    }

    public String getExtensionStatus() {
        return extensionStatus;
    }

    public void setExtensionStatus(String extensionStatus) {
        this.extensionStatus = extensionStatus;
    }

    public void setBarangay(Barangay barangay) {
        this.barangay = barangay;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public String getReportedBy() {
        return reportedBy;
    }

    public void setReportedBy(String reportedBy) {
        this.reportedBy = reportedBy;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public LocalDate getDateSubmitted() {
        return dateSubmitted;
    }

    public void setDateSubmitted(LocalDate dateSubmitted) {
        this.dateSubmitted = dateSubmitted;
    }

    public String getAdminRemarks() {
        return adminRemarks;
    }

    public void setAdminRemarks(String adminRemarks) {
        this.adminRemarks = adminRemarks;
    }

    public String getDamageType() {
        return damageType;
    }

    public void setDamageType(String damageType) {
        this.damageType = damageType;
    }

    public Double getDamageLength() {
        return damageLength;
    }

    public void setDamageLength(Double damageLength) {
        this.damageLength = damageLength;
    }

    public Double getDamageWidth() {
        return damageWidth;
    }

    public void setDamageWidth(Double damageWidth) {
        this.damageWidth = damageWidth;
    }

    public LocalDateTime getDateArchived() {
        return dateArchived;
    }

    public void setDateArchived(LocalDateTime dateArchived) {
        this.dateArchived = dateArchived;
    }

    public LocalDate getTargetCompletionDate() {
        return targetCompletionDate;
    }

    public void setTargetCompletionDate(LocalDate targetCompletionDate) {
        this.targetCompletionDate = targetCompletionDate;
    }

    public LocalDate getActualCompletionDate() {
        return actualCompletionDate;
    }

    public void setActualCompletionDate(LocalDate actualCompletionDate) {
        this.actualCompletionDate = actualCompletionDate;
    }
}
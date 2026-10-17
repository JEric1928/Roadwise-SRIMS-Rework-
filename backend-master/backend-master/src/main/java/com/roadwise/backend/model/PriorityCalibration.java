package com.roadwise.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * The single RLS calibration row (V20 {@code priority_calibration}).
 *
 * <p>One row, enforced by {@code chk_priority_calibration_singleton}. Weights start at
 * 1.0, so before any audit is recorded the adaptive score reproduces the static
 * ranking exactly; calibration only bends the ordering once real field audits exist.</p>
 *
 * <p>{@code covarianceJson} holds the 4x4 RLS covariance matrix (16 values, row-major)
 * as text. TEXT rather than JSONB deliberately: Hibernate's schema validator compares
 * JDBC types, and JSONB behind a {@code String} field is a boot-time validation
 * failure. Only {@code RecursiveLeastSquares} reads or writes it.</p>
 */
@Entity
@Table(name = "priority_calibration")
public class PriorityCalibration {

    /** The table admits exactly one row; see the CHECK constraint in V20. */
    public static final short SINGLETON_ID = 1;

    @Id
    private Short id = SINGLETON_ID;

    @Column(name = "weight_severity", nullable = false)
    private Double weightSeverity = 1.0;

    @Column(name = "weight_core_arterial", nullable = false)
    private Double weightCoreArterial = 1.0;

    @Column(name = "weight_area", nullable = false)
    private Double weightArea = 1.0;

    @Column(name = "weight_depth", nullable = false)
    private Double weightDepth = 1.0;

    @Column(name = "covariance_json", columnDefinition = "TEXT")
    private String covarianceJson;

    @Column(name = "audit_count", nullable = false)
    private Integer auditCount = 0;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public PriorityCalibration() {
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public Short getId() { return id; }
    public void setId(Short id) { this.id = id; }

    public Double getWeightSeverity() { return weightSeverity; }
    public void setWeightSeverity(Double weightSeverity) { this.weightSeverity = weightSeverity; }

    public Double getWeightCoreArterial() { return weightCoreArterial; }
    public void setWeightCoreArterial(Double weightCoreArterial) { this.weightCoreArterial = weightCoreArterial; }

    public Double getWeightArea() { return weightArea; }
    public void setWeightArea(Double weightArea) { this.weightArea = weightArea; }

    public Double getWeightDepth() { return weightDepth; }
    public void setWeightDepth(Double weightDepth) { this.weightDepth = weightDepth; }

    public String getCovarianceJson() { return covarianceJson; }
    public void setCovarianceJson(String covarianceJson) { this.covarianceJson = covarianceJson; }

    public Integer getAuditCount() { return auditCount; }
    public void setAuditCount(Integer auditCount) { this.auditCount = auditCount; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}

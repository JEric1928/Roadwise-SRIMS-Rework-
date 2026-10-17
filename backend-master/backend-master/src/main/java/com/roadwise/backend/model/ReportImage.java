package com.roadwise.backend.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * One uploaded photo belonging to one inspection report (V20 {@code report_images}).
 *
 * <p>Replaces the single {@code road_reports.damage_image} column for new
 * submissions. That legacy column is still populated for backward compatibility and
 * is the backfill source for rows created before V20.</p>
 *
 * <p>The per-image inference columns are an audit trail, not the source of the
 * report's priority: {@code RoadReport.cvSeverityScore} holds the aggregated
 * (maximum) class across every image, so the reason a report ranked where it did can
 * be re-derived without re-running the model.</p>
 */
@Entity
@Table(name = "report_images")
public class ReportImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Owning report. {@link JsonIgnore} is deliberate: {@link RoadReport} serializes
     * directly to clients on several legacy endpoints, and a back-reference here would
     * make that graph infinite (and trip a lazy-initialization read). Images are
     * exposed through {@code ReportImageDto} instead.
     */
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "report_id", nullable = false)
    private RoadReport report;

    @Column(name = "image_url", nullable = false, columnDefinition = "TEXT")
    private String imageUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "perspective_type", nullable = false, length = 30)
    private ImagePerspective perspectiveType = ImagePerspective.CLOSE_UP;

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder = 0;

    /** Stage-1 gate verdict. Null means inference never ran for this image. */
    @Column(name = "gate_passed")
    private Boolean gatePassed;

    /** Stage-2 severity class for *this* image, 1-3. */
    @Column(name = "cv_severity_score")
    private Integer cvSeverityScore;

    @Column(name = "cv_confidence_score")
    private Double cvConfidenceScore;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public ReportImage() {
    }

    public ReportImage(RoadReport report, String imageUrl, ImagePerspective perspectiveType) {
        this.report = report;
        this.imageUrl = imageUrl;
        this.perspectiveType = perspectiveType;
        this.displayOrder = perspectiveType != null ? perspectiveType.displayOrder() : 0;
    }

    /**
     * Keeps {@code display_order} consistent with the perspective without callers
     * having to remember to set both. Runs before every insert because the column is
     * NOT NULL with a database default that would otherwise hide a caller's omission.
     */
    @PrePersist
    public void prePersist() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
        if (this.displayOrder == null) {
            this.displayOrder = this.perspectiveType != null ? this.perspectiveType.displayOrder() : 0;
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public RoadReport getReport() { return report; }
    public void setReport(RoadReport report) { this.report = report; }

    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }

    public ImagePerspective getPerspectiveType() { return perspectiveType; }
    public void setPerspectiveType(ImagePerspective perspectiveType) { this.perspectiveType = perspectiveType; }

    public Integer getDisplayOrder() { return displayOrder; }
    public void setDisplayOrder(Integer displayOrder) { this.displayOrder = displayOrder; }

    public Boolean getGatePassed() { return gatePassed; }
    public void setGatePassed(Boolean gatePassed) { this.gatePassed = gatePassed; }

    public Integer getCvSeverityScore() { return cvSeverityScore; }
    public void setCvSeverityScore(Integer cvSeverityScore) { this.cvSeverityScore = cvSeverityScore; }

    public Double getCvConfidenceScore() { return cvConfidenceScore; }
    public void setCvConfidenceScore(Double cvConfidenceScore) { this.cvConfidenceScore = cvConfidenceScore; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}

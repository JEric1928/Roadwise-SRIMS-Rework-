package com.roadwise.backend.dto;

import com.roadwise.backend.model.RoadReport;
import java.time.LocalDate;
import java.time.LocalDateTime;

public class ReportListDto {
    private Long id;
    private String cityRoadName;
    private String cityRoadId;
    private String status;
    private String severity;
    private Long barangayId;
    private String barangayName;
    private String reportedBy;
    private LocalDate dateSubmitted;
    private LocalDateTime targetCompletionDate;
    private Double cvConfidenceScore;
    private String damageType;
    private String damageImage;

    public ReportListDto() {}

    public static ReportListDto fromEntity(RoadReport r) {
        ReportListDto dto = new ReportListDto();
        dto.setId(r.getId());
        dto.setCityRoadName(r.getCityRoadName());
        dto.setCityRoadId(r.getCityRoadId());
        dto.setStatus(r.getStatus());
        dto.setSeverity(r.getSeverity());
        if (r.getBarangay() != null) {
            dto.setBarangayId(r.getBarangay().getId());
            dto.setBarangayName(r.getBarangay().getBarangayName());
        }
        dto.setReportedBy(r.getReportedBy());
        dto.setDateSubmitted(r.getDateSubmitted());
        dto.setCvConfidenceScore(r.getCvConfidenceScore());
        dto.setDamageType(r.getDamageType());
        dto.setDamageImage(r.getDamageImage());
        return dto;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getCityRoadName() { return cityRoadName; }
    public void setCityRoadName(String cityRoadName) { this.cityRoadName = cityRoadName; }

    public String getCityRoadId() { return cityRoadId; }
    public void setCityRoadId(String cityRoadId) { this.cityRoadId = cityRoadId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }

    public Long getBarangayId() { return barangayId; }
    public void setBarangayId(Long barangayId) { this.barangayId = barangayId; }

    public String getBarangayName() { return barangayName; }
    public void setBarangayName(String barangayName) { this.barangayName = barangayName; }

    public String getReportedBy() { return reportedBy; }
    public void setReportedBy(String reportedBy) { this.reportedBy = reportedBy; }

    public LocalDate getDateSubmitted() { return dateSubmitted; }
    public void setDateSubmitted(LocalDate dateSubmitted) { this.dateSubmitted = dateSubmitted; }

    public Double getCvConfidenceScore() { return cvConfidenceScore; }
    public void setCvConfidenceScore(Double cvConfidenceScore) { this.cvConfidenceScore = cvConfidenceScore; }

    public String getDamageType() { return damageType; }
    public void setDamageType(String damageType) { this.damageType = damageType; }

    public String getDamageImage() { return damageImage; }
    public void setDamageImage(String damageImage) { this.damageImage = damageImage; }
}

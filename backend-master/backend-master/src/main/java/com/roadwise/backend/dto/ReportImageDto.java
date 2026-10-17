package com.roadwise.backend.dto;

import com.roadwise.backend.model.ReportImage;

/**
 * Read projection for one attachment.
 *
 * <p>Exists because {@code RoadReport} is serialized directly to clients on several
 * legacy endpoints, so its {@code images} association is {@code @JsonIgnore}d to keep
 * the object graph finite. New endpoints return this projection instead, which also
 * stops the entity's lazy {@code report} back-reference from being touched during
 * serialization outside a transaction.</p>
 */
public class ReportImageDto {

    private Long id;
    private String imageUrl;
    private String perspectiveType;
    private Integer displayOrder;
    private Boolean gatePassed;
    private Integer cvSeverityScore;
    private Double cvConfidenceScore;

    public ReportImageDto() {
    }

    public static ReportImageDto from(ReportImage image) {
        ReportImageDto dto = new ReportImageDto();
        dto.id = image.getId();
        dto.imageUrl = image.getImageUrl();
        dto.perspectiveType = image.getPerspectiveType() == null ? null : image.getPerspectiveType().name();
        dto.displayOrder = image.getDisplayOrder();
        dto.gatePassed = image.getGatePassed();
        dto.cvSeverityScore = image.getCvSeverityScore();
        dto.cvConfidenceScore = image.getCvConfidenceScore();
        return dto;
    }

    public Long getId() { return id; }
    public String getImageUrl() { return imageUrl; }
    public String getPerspectiveType() { return perspectiveType; }
    public Integer getDisplayOrder() { return displayOrder; }
    public Boolean getGatePassed() { return gatePassed; }
    public Integer getCvSeverityScore() { return cvSeverityScore; }
    public Double getCvConfidenceScore() { return cvConfidenceScore; }
}

package com.roadwise.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The barangay field inspector's entire upload: photos, a pin, and a category.
 *
 * <p>Bound from {@code multipart/form-data}. The photos themselves are not fields on
 * this record — they arrive as a {@code MultipartFile[]} parameter so the controller
 * can enforce the 3-photo / per-perspective limit against the actual parts rather
 * than against a JSON array a client could inflate.</p>
 *
 * <p>Deliberately absent: {@code damageLength}, {@code damageWidth},
 * {@code damageDepth}, {@code surfaceType}, {@code cpdoRepairPlan}. Those are
 * engineering fields owned by {@link CpdoSurveyDto}. Keeping them out of this object
 * is what makes the decoupling real — a barangay request that includes them has no
 * field to bind to.</p>
 */
public class BarangayReportCreateDto {

    /** One of the four {@code DefectCategory} labels. Validated against the enum. */
    @NotBlank(message = "Defect category is required.")
    private String damageType;

    /** WGS-84 latitude from the browser geolocation API or the Leaflet fallback. */
    @NotNull(message = "Latitude is required — capture a GPS pin before submitting.")
    private Double latitude;

    /** WGS-84 longitude. */
    @NotNull(message = "Longitude is required — capture a GPS pin before submitting.")
    private Double longitude;

    /**
     * Optional registered road linkage. Accepts either the numeric database id or the
     * human-readable road code, matching the resolution the existing submit path does.
     * When omitted the report is stored with only the coordinate, and CPDO attaches
     * the road during verification.
     */
    private String cityRoadId;

    /** Optional denormalised road name snapshot for display without a join. */
    @Size(max = 255, message = "Road name is too long.")
    private String cityRoadName;

    /** Free-text note allowed but not required — the form is meant to stay 3 fields. */
    @Size(max = 2000, message = "Description is too long.")
    private String damageDescription;

    public BarangayReportCreateDto() {
    }

    public String getDamageType() { return damageType; }
    public void setDamageType(String damageType) { this.damageType = damageType; }

    public Double getLatitude() { return latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }

    public Double getLongitude() { return longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }

    public String getCityRoadId() { return cityRoadId; }
    public void setCityRoadId(String cityRoadId) { this.cityRoadId = cityRoadId; }

    public String getCityRoadName() { return cityRoadName; }
    public void setCityRoadName(String cityRoadName) { this.cityRoadName = cityRoadName; }

    public String getDamageDescription() { return damageDescription; }
    public void setDamageDescription(String damageDescription) { this.damageDescription = damageDescription; }
}

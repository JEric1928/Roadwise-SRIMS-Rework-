package com.roadwise.backend.dto;


public class ReportCreateDto {
    private String damageDescription;
    private String damageType;
    private Double length;
    private Double width;
    private Double lengthOfCulverts;
    private Integer numberOfBridges;
    private Double latitude;
    private Double longitude;
    private Double damageLength;
    private Double damageWidth;
    // Accepts either the database id or the human-readable road code; the
    // controller resolves whichever the client sent.
    private String cityRoadId;
    private String cityRoadName;

    public ReportCreateDto() {}

    public String getDamageDescription() { return damageDescription; }
    public void setDamageDescription(String damageDescription) { this.damageDescription = damageDescription; }

    public String getDamageType() { return damageType; }
    public void setDamageType(String damageType) { this.damageType = damageType; }

    public Double getLength() { return length; }
    public void setLength(Double length) { this.length = length; }

    public Double getWidth() { return width; }
    public void setWidth(Double width) { this.width = width; }

    public Double getLengthOfCulverts() { return lengthOfCulverts; }
    public void setLengthOfCulverts(Double lengthOfCulverts) { this.lengthOfCulverts = lengthOfCulverts; }

    public Integer getNumberOfBridges() { return numberOfBridges; }
    public void setNumberOfBridges(Integer numberOfBridges) { this.numberOfBridges = numberOfBridges; }

    public Double getLatitude() { return latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }

    public Double getLongitude() { return longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }

    public Double getDamageLength() { return damageLength; }
    public void setDamageLength(Double damageLength) { this.damageLength = damageLength; }

    public Double getDamageWidth() { return damageWidth; }
    public void setDamageWidth(Double damageWidth) { this.damageWidth = damageWidth; }

    public String getCityRoadId() { return cityRoadId; }
    public void setCityRoadId(String cityRoadId) { this.cityRoadId = cityRoadId; }

    public String getCityRoadName() { return cityRoadName; }
    public void setCityRoadName(String cityRoadName) { this.cityRoadName = cityRoadName; }
}

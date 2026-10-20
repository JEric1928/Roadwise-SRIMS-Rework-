package com.roadwise.backend.dto;

import com.roadwise.backend.model.Barangay;

public class BarangayResponseDto {

    private Long id;
    private String barangayName;
    private String brgyCaptain;
    private String contactNumber;
    private String emailAddress;

    public BarangayResponseDto() {
    }

    public BarangayResponseDto(Long id, String barangayName, String brgyCaptain, String contactNumber, String emailAddress) {
        this.id = id;
        this.barangayName = barangayName;
        this.brgyCaptain = brgyCaptain;
        this.contactNumber = contactNumber;
        this.emailAddress = emailAddress;
    }

    public static BarangayResponseDto from(Barangay barangay, String officialName, String contactNumber, String emailAddress, boolean includeOfficialDetails) {
        if (barangay == null) {
            return null;
        }
        if (includeOfficialDetails) {
            return new BarangayResponseDto(barangay.getId(), barangay.getBarangayName(), officialName, contactNumber, emailAddress);
        } else {
            return new BarangayResponseDto(barangay.getId(), barangay.getBarangayName(), null, null, null);
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getBarangayName() { return barangayName; }
    public void setBarangayName(String barangayName) { this.barangayName = barangayName; }

    public String getBrgyCaptain() { return brgyCaptain; }
    public void setBrgyCaptain(String brgyCaptain) { this.brgyCaptain = brgyCaptain; }

    public String getContactNumber() { return contactNumber; }
    public void setContactNumber(String contactNumber) { this.contactNumber = contactNumber; }

    public String getEmailAddress() { return emailAddress; }
    public void setEmailAddress(String emailAddress) { this.emailAddress = emailAddress; }
}

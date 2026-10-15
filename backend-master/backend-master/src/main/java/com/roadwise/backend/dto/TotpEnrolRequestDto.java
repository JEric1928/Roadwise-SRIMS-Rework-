package com.roadwise.backend.dto;

/** Optional label so a user with several authenticators can tell them apart. */
public class TotpEnrolRequestDto {

    private String label;

    public TotpEnrolRequestDto() {
    }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
}

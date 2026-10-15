package com.roadwise.backend.dto;

/**
 * A factor the user may choose from at the login prompt.
 *
 * <p>Produced only by the server, from the account's real enrolment. The frontend
 * renders exactly this list and can ask for nothing else.
 */
public class MfaFactorOptionDto {

    private String type;
    private String label;

    public MfaFactorOptionDto() {
    }

    public MfaFactorOptionDto(String type, String label) {
        this.type = type;
        this.label = label;
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
}

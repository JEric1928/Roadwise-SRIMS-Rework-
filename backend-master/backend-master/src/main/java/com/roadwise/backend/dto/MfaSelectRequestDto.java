package com.roadwise.backend.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Chooses which enrolled factor the in-flight login should verify against.
 *
 * <p>The requested type is only a preference. The server re-derives the caller's
 * enrolled factors and rejects anything that is not actually available, so a client
 * cannot promote itself to a factor it does not have.
 */
public class MfaSelectRequestDto {

    @NotBlank(message = "MFA session token is required")
    private String mfaToken;

    @NotBlank(message = "Factor type is required")
    private String factorType;

    public MfaSelectRequestDto() {
    }

    public String getMfaToken() { return mfaToken; }
    public void setMfaToken(String mfaToken) { this.mfaToken = mfaToken; }

    public String getFactorType() { return factorType; }
    public void setFactorType(String factorType) { this.factorType = factorType; }
}

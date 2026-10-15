package com.roadwise.backend.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Password re-entry for step-up authentication.
 *
 * <p>Only the password. A second MFA ceremony here would be stronger, but the threat this
 * defends against is a hijacked session, and whoever stole a token does not have the
 * password. Re-running the factor the caller just used would add lockout risk to a
 * settings screen for very little marginal assurance.
 */
public class StepUpRequestDto {

    @NotBlank(message = "Password is required")
    private String password;

    public StepUpRequestDto() {
    }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
}

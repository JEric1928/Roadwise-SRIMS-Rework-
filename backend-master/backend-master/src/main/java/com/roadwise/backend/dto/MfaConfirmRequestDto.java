package com.roadwise.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Completes TOTP enrolment by proving a code can be generated from the new secret. */
public class MfaConfirmRequestDto {

    @NotNull(message = "Factor id is required")
    private Long factorId;

    @NotBlank(message = "Verification code is required")
    private String code;

    public MfaConfirmRequestDto() {
    }

    public Long getFactorId() { return factorId; }
    public void setFactorId(Long factorId) { this.factorId = factorId; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
}

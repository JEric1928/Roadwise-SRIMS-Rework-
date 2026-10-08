package com.roadwise.backend.dto;

import jakarta.validation.constraints.NotNull;

public class MfaVerifyRequestDto {
    @NotNull(message = "User ID is required")
    private Long userId;

    @NotNull(message = "OTP is required")
    private String otp;

    public MfaVerifyRequestDto() {}

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getOtp() { return otp; }
    public void setOtp(String otp) { this.otp = otp; }
}

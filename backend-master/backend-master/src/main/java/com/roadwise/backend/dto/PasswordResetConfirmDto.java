package com.roadwise.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class PasswordResetConfirmDto {
    @NotNull(message = "User ID is required")
    private Long userId;

    @NotBlank(message = "OTP recovery code is required")
    private String otp;

    @NotBlank(message = "New password is required")
    private String newPassword;

    public PasswordResetConfirmDto() {}

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getOtp() { return otp; }
    public void setOtp(String otp) { this.otp = otp; }

    public String getNewPassword() { return newPassword; }
    public void setNewPassword(String newPassword) { this.newPassword = newPassword; }
}

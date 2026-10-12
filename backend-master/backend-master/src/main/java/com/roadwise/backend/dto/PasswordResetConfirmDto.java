package com.roadwise.backend.dto;

import jakarta.validation.constraints.NotBlank;

public class PasswordResetConfirmDto {
    @NotBlank(message = "Email is required")
    private String email;

    @NotBlank(message = "OTP recovery code is required")
    private String otp;

    @NotBlank(message = "New password is required")
    private String newPassword;

    public PasswordResetConfirmDto() {}

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getOtp() { return otp; }
    public void setOtp(String otp) { this.otp = otp; }

    public String getNewPassword() { return newPassword; }
    public void setNewPassword(String newPassword) { this.newPassword = newPassword; }
}

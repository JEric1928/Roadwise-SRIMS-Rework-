package com.roadwise.backend.dto;

import jakarta.validation.constraints.NotBlank;

public class PasswordResetRequestDto {
    @NotBlank(message = "Email is required")
    private String email;

    public PasswordResetRequestDto() {}

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
}

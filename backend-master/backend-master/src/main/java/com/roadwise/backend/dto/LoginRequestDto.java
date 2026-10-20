package com.roadwise.backend.dto;

import jakarta.validation.constraints.NotBlank;

public class LoginRequestDto {
    @NotBlank(message = "Username or email is required")
    private String username;

    @NotBlank(message = "Password is required")
    private String password;

    /**
     * Cloudflare Turnstile token produced by the widget on the login page.
     *
     * <p>Deliberately not annotated with {@code @NotBlank}: a missing token has to come back
     * from the login handler as a 403 with something the user can act on, not as a generic
     * 400 validation failure. The handler treats null exactly like a rejected token -- the
     * login is refused, see {@code TurnstileService}.</p>
     */
    private String turnstileToken;

    public LoginRequestDto() {}

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getTurnstileToken() { return turnstileToken; }
    public void setTurnstileToken(String turnstileToken) { this.turnstileToken = turnstileToken; }
}

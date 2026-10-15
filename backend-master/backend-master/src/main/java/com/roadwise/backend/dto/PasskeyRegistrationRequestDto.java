package com.roadwise.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Completes a registration ceremony: which ceremony, what the authenticator produced,
 * and what the passkey should be called.
 */
public class PasskeyRegistrationRequestDto {

    @NotBlank(message = "Ceremony key is required")
    private String ceremonyKey;

    @Valid
    @NotNull(message = "The authenticator's response is required")
    private WebAuthnCredentialDto credential;

    private String label;

    public String getCeremonyKey() { return ceremonyKey; }
    public void setCeremonyKey(String ceremonyKey) { this.ceremonyKey = ceremonyKey; }

    public WebAuthnCredentialDto getCredential() { return credential; }
    public void setCredential(WebAuthnCredentialDto credential) { this.credential = credential; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
}
package com.roadwise.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * Second step of the login ceremony.
 *
 * <p>Carries the opaque token minted by {@code /api/auth/login} instead of a user id.
 * That change is the point: the previous shape let a caller name any account and have
 * the server look up "the most recent unused challenge" for it, which made the
 * challenge unbound to the login attempt that created it.
 *
 * <p>Which proof is required depends on the factor the challenge selected, so neither
 * {@code code} nor {@code assertion} is universally mandatory: email OTP and TOTP need
 * {@code code}, a passkey needs {@code ceremonyKey} plus {@code assertion}. The
 * controller enforces the per-factor requirement against the server-side challenge --
 * what the client claims it is allowed to use is never trusted.
 */
public class MfaVerifyRequestDto {

    @NotBlank(message = "MFA session token is required")
    private String mfaToken;

    /** The 6-digit code or recovery code. Unused for passkey verification. */
    private String code;

    /** The one-time ceremony key issued by /mfa/select for a passkey assertion. */
    private String ceremonyKey;

    /** The authenticator's assertion, in the browser's WebAuthn shape. */
    @Valid
    private WebAuthnCredentialDto assertion;

    public MfaVerifyRequestDto() {
    }

    public String getMfaToken() { return mfaToken; }
    public void setMfaToken(String mfaToken) { this.mfaToken = mfaToken; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getCeremonyKey() { return ceremonyKey; }
    public void setCeremonyKey(String ceremonyKey) { this.ceremonyKey = ceremonyKey; }

    public WebAuthnCredentialDto getAssertion() { return assertion; }
    public void setAssertion(WebAuthnCredentialDto assertion) { this.assertion = assertion; }
}
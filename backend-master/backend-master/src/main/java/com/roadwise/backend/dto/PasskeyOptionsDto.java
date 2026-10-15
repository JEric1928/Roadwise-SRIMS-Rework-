package com.roadwise.backend.dto;

/**
 * A ceremony's one-time key plus the options the browser needs to run it.
 *
 * <p>The key is shown to the client exactly once here and only its digest is stored
 * (see {@code WebAuthnCeremonyService}); the client must present it back with the
 * authenticator's response, which is what ties that response to this ceremony.
 */
public record PasskeyOptionsDto<T>(String ceremonyKey, T options) {
}
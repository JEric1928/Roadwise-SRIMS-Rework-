package com.roadwise.backend.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256 hex of opaque bearer values before persistence.
 *
 * <p>One canonical place for it. MFA challenge tokens, ceremony keys, password-reset
 * tokens and refresh tokens all follow the same rule -- the raw value is shown once and
 * only its digest is stored -- and having two copies of the digest function is how one
 * of them quietly ends up still on MD5.
 */
public final class TokenHashes {

    private TokenHashes() {
    }

    public static String sha256Hex(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available in this JVM", e);
        }
    }
}

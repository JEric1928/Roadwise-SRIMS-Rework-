package com.roadwise.backend.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM envelope for TOTP shared secrets.
 *
 * <p>Every other credential in this application is hashed, because the server only
 * ever needs to confirm it, never reproduce it. A TOTP secret is the exception:
 * recomputing the expected code requires the original bytes, so it has to be
 * encrypted with a key the database never sees.
 *
 * <p>Each ciphertext is bound to its owning account through the GCM additional
 * authenticated data. Moving a ciphertext onto another user's row therefore fails
 * authentication rather than silently handing that user someone else's factor.
 *
 * <p><strong>Fail-closed by design.</strong> With no configured key,
 * {@link #isConfigured()} reports false and every encrypt/decrypt throws. There is
 * deliberately no fallback key: a hardcoded fallback is indistinguishable from no
 * encryption at all, which is the failure mode that makes this class worth having.
 * Email OTP is unaffected and keeps working when TOTP is unconfigured.
 */
@Service
public class MfaSecretCipher {

    /** 96-bit nonce, the length AES-GCM is specified for. */
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final int AES_256_KEY_BYTES = 32;

    /** Stamped onto every ciphertext so a rotation job can identify old material. */
    public static final int CURRENT_KEY_VERSION = 1;

    @Value("${mfa.encryption-key:}")
    private String encryptionKey;

    private final SecureRandom secureRandom = new SecureRandom();
    private SecretKey key;

    @PostConstruct
    void init() {
        if (encryptionKey == null || encryptionKey.isBlank()) {
            key = null;
            System.err.println("⚠️ [MFA] mfa.encryption-key is not set (env MFA_ENCRYPTION_KEY). "
                    + "TOTP enrolment is disabled; email OTP MFA continues to work. "
                    + "Generate one with: openssl rand -base64 32");
            return;
        }

        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encryptionKey.trim());
        } catch (IllegalArgumentException e) {
            // Misconfiguration is a startup failure, not a runtime surprise: a
            // half-configured key must not look like a working one.
            throw new IllegalStateException(
                    "mfa.encryption-key is invalid: expected a Base64-encoded 32-byte value.", e);
        }
        if (decoded.length != AES_256_KEY_BYTES) {
            throw new IllegalStateException(
                    "mfa.encryption-key must decode to exactly 32 bytes for AES-256, but was "
                            + decoded.length + " bytes.");
        }
        this.key = new SecretKeySpec(decoded, "AES");
    }

    /** @return true when TOTP secrets can be stored. False disables TOTP enrolment only. */
    public boolean isConfigured() {
        return key != null;
    }

    /**
     * @return Base64 of {@code IV || ciphertext||tag}, with the key version
     *         persisted separately on the factor row.
     */
    public String encrypt(String plaintext, long userId) {
        requireConfigured();
        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            cipher.updateAAD(additionalAuthenticatedData(userId));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] packed = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, packed, 0, iv.length);
            System.arraycopy(ciphertext, 0, packed, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(packed);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to encrypt MFA secret.", e);
        }
    }

    /**
     * @throws IllegalStateException when the envelope is malformed, the key is
     *         wrong, or the ciphertext was bound to a different account.
     */
    public String decrypt(String stored, long userId) {
        requireConfigured();
        if (stored == null || stored.isBlank()) {
            throw new IllegalStateException("No MFA secret is stored for this factor.");
        }
        try {
            byte[] packed = Base64.getDecoder().decode(stored);
            if (packed.length <= IV_LENGTH) {
                throw new IllegalStateException("Stored MFA secret is truncated.");
            }
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(packed, 0, iv, 0, IV_LENGTH);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            cipher.updateAAD(additionalAuthenticatedData(userId));
            byte[] plaintext = cipher.doFinal(packed, IV_LENGTH, packed.length - IV_LENGTH);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(
                    "Failed to decrypt MFA secret: wrong key, tampered envelope, or factor/user mismatch.", e);
        }
    }

    private void requireConfigured() {
        if (key == null) {
            throw new IllegalStateException(
                    "mfa.encryption-key is not configured; TOTP secrets cannot be stored or read.");
        }
    }

    private static byte[] additionalAuthenticatedData(long userId) {
        return ("roadwise-mfa-factor:" + userId).getBytes(StandardCharsets.UTF_8);
    }
}

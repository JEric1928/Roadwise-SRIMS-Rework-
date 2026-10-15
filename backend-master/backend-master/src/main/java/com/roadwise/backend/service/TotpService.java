package com.roadwise.backend.service;

import com.roadwise.backend.util.Base32;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Optional;

/**
 * RFC 6238 TOTP over RFC 4226 HOTP (HMAC-SHA1, 6 digits, 30-second step), the
 * profile every authenticator app implements.
 *
 * <p>Two details that are easy to get wrong and matter here:
 *
 * <ul>
 *   <li><strong>Clock drift.</strong> Server and phone clocks never agree exactly,
 *       so a window of {@value #DRIFT_STEPS} step either side is accepted. That
 *       widens the guessable surface, which is why the attempt ceiling on the
 *       challenge is what actually bounds brute force.</li>
 *   <li><strong>Replay.</strong> Accepting a window means the same code stays valid
 *       for ~90 seconds. The caller passes in the step of the last accepted code so
 *       a code observed over someone's shoulder cannot be reused inside that
 *       window; see {@code MfaFactor#lastTotpStep}.</li>
 * </ul>
 *
 * <p>Sole crypto note: {@code HmacSHA1} is not a weakness choice here. HOTP/TOTP
 * fixes SHA-1 as the interoperable default and the construction is HMAC, not a
 * signature, so collision resistance is not the property being relied on.
 */
@Service
public class TotpService {

    private static final int DIGITS = 6;
    private static final long STEP_SECONDS = 30L;
    private static final int DRIFT_STEPS = 1;
    private static final int SECRET_BYTES = 20; // 160 bits, RFC 4226 section 4
    private static final String HMAC_ALGORITHM = "HmacSHA1";

    private final SecureRandom secureRandom = new SecureRandom();

    /** @return a fresh Base32 secret suitable for an {@code otpauth://} URI. */
    public String generateSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        secureRandom.nextBytes(bytes);
        return Base32.encode(bytes);
    }

    /** @return the RFC 6238 time-step counter for now. */
    public long currentStep() {
        return Instant.now().getEpochSecond() / STEP_SECONDS;
    }

    public long secondsRemainingInCurrentStep() {
        return STEP_SECONDS - (Instant.now().getEpochSecond() % STEP_SECONDS);
    }

    /**
     * Verifies a submitted code against the secret.
     *
     * @param base32Secret the decrypted factor secret
     * @param submitted    the user-supplied code, which may contain spaces
     * @param lastAcceptedStep step of the most recently accepted code, or null when
     *        the factor has never been used. Codes at or below this step are refused.
     * @return the matched time-step, to be persisted as the new
     *         {@code lastTotpStep} so the same code cannot be replayed.
     */
    public Optional<Long> verify(String base32Secret, String submitted, Long lastAcceptedStep) {
        if (submitted == null) {
            return Optional.empty();
        }
        String normalized = submitted.replace(" ", "").replace("-", "").trim();
        if (normalized.length() != DIGITS || !normalized.chars().allMatch(Character::isDigit)) {
            return Optional.empty();
        }

        long currentStep = currentStep();
        byte[] submittedBytes = normalized.getBytes(StandardCharsets.UTF_8);

        // Walk the whole window before deciding, so timing does not reveal *which*
        // step matched (or that none did) beyond the constant-time comparison itself.
        Optional<Long> match = Optional.empty();
        for (long step = currentStep - DRIFT_STEPS; step <= currentStep + DRIFT_STEPS; step++) {
            String expected = codeAt(base32Secret, step);
            boolean equal = MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8), submittedBytes);
            if (equal && isReplayable(step, lastAcceptedStep) && match.isEmpty()) {
                match = Optional.of(step);
            }
        }
        return match;
    }

    /**
     * Builds the provisioning URI an authenticator app consumes when it scans the
     * enrolment QR code.
     */
    public String provisioningUri(String issuer, String accountName, String base32Secret) {
        String label = urlEncode(issuer) + ":" + urlEncode(accountName);
        return "otpauth://totp/" + label
                + "?secret=" + base32Secret
                + "&issuer=" + urlEncode(issuer)
                + "&algorithm=SHA1"
                + "&digits=" + DIGITS
                + "&period=" + STEP_SECONDS;
    }

    /** Computes the code for an explicit step. Package-visible for tests. */
    String codeAt(String base32Secret, long step) {
        byte[] key = Base32.decode(base32Secret);
        byte[] counter = ByteBuffer.allocate(8).putLong(step).array();
        byte[] hash;
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            hash = mac.doFinal(counter);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to compute TOTP code.", e);
        }

        // Dynamic truncation, RFC 4226 section 5.4.
        int offset = hash[hash.length - 1] & 0x0F;
        int binary = ((hash[offset] & 0x7F) << 24)
                | ((hash[offset + 1] & 0xFF) << 16)
                | ((hash[offset + 2] & 0xFF) << 8)
                | (hash[offset + 3] & 0xFF);
        int otp = binary % 1_000_000;
        return String.format("%0" + DIGITS + "d", otp);
    }

    private static boolean isReplayable(long step, Long lastAcceptedStep) {
        return lastAcceptedStep == null || step > lastAcceptedStep;
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8)
                .replace("+", "%20");
    }
}

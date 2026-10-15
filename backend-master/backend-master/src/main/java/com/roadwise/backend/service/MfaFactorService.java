package com.roadwise.backend.service;

import com.roadwise.backend.model.MfaFactor;
import com.roadwise.backend.model.User;
import com.roadwise.backend.repository.MfaFactorRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Lifecycle and verification for enrolled factors.
 *
 * <p>Enrolment is deliberately two-step: {@link #enrolTotp} stores a {@code PENDING}
 * factor, and only {@link #confirmTotp} — which requires a working code — promotes it
 * to {@code ACTIVE}. Skipping that check is how users end up locked out by a secret
 * their authenticator never actually received.
 */
@Service
public class MfaFactorService {

    public static final String ISSUER = "RoadWise CSJDM";

    /** A pending enrolment is a half-finished ceremony, not a credential. Keep it short. */
    private static final int ENROLMENT_MINUTES = 10;

    private final MfaFactorRepository mfaFactorRepository;
    private final MfaSecretCipher mfaSecretCipher;
    private final TotpService totpService;
    private final MfaPolicyService mfaPolicyService;

    public MfaFactorService(MfaFactorRepository mfaFactorRepository,
                            MfaSecretCipher mfaSecretCipher,
                            TotpService totpService,
                            MfaPolicyService mfaPolicyService) {
        this.mfaFactorRepository = mfaFactorRepository;
        this.mfaSecretCipher = mfaSecretCipher;
        this.totpService = totpService;
        this.mfaPolicyService = mfaPolicyService;
    }

    public List<MfaFactor> list(User user) {
        return mfaFactorRepository.findByUserIdOrderByCreatedAtDesc(user.getId());
    }

    public boolean isTotpEnrolmentAvailable() {
        return mfaSecretCipher.isConfigured();
    }

    /**
     * Starts TOTP enrolment.
     *
     * @return the new factor id, its Base32 secret and the {@code otpauth://} URI to
     *         render as a QR code. The secret is returned exactly once, here.
     * @throws IllegalStateException when the server has no encryption key, or when an
     *         active TOTP factor already exists
     */
    @Transactional
    public TotpEnrolment enrolTotp(User user, String label) {
        if (!mfaSecretCipher.isConfigured()) {
            throw new IllegalStateException(
                    "TOTP is not available on this server: mfa.encryption-key (env MFA_ENCRYPTION_KEY) is not configured.");
        }

        Optional<MfaFactor> existing = mfaFactorRepository
                .findFirstByUserIdAndFactorTypeAndStatusNotOrderByCreatedAtDesc(
                        user.getId(), MfaFactor.TYPE_TOTP, MfaFactor.STATUS_DISABLED);
        if (existing.isPresent() && existing.get().isActive()) {
            // Refusing is the safe behaviour: silently minting a second secret would
            // invalidate the working authenticator without saying so.
            throw new IllegalStateException(
                    "An authenticator app is already enrolled. Remove it before enrolling a new one.");
        }
        // A previous abandoned enrolment is unverified, so replacing it costs nothing.
        existing.ifPresent(mfaFactorRepository::delete);

        String secret = totpService.generateSecret();
        MfaFactor factor = new MfaFactor(
                user,
                MfaFactor.TYPE_TOTP,
                label == null || label.isBlank() ? defaultLabel() : label.trim(),
                MfaFactor.STATUS_PENDING);
        factor.setSecretCiphertext(mfaSecretCipher.encrypt(secret, user.getId()));
        factor.setSecretKeyVersion(MfaSecretCipher.CURRENT_KEY_VERSION);
        mfaFactorRepository.save(factor);

        String accountName = user.getEmail() != null && !user.getEmail().isBlank()
                ? user.getEmail()
                : user.getUsername();
        return new TotpEnrolment(
                factor.getId(),
                secret,
                totpService.provisioningUri(ISSUER, accountName, secret),
                ENROLMENT_MINUTES);
    }

    /**
     * Completes TOTP enrolment by proving the user can produce a valid code.
     *
     * @throws IllegalStateException when the factor is unknown, not the caller's, not
     *         pending, or the code does not match
     */
    @Transactional
    public MfaFactor confirmTotp(User user, Long factorId, String code) {
        MfaFactor factor = mfaFactorRepository.findByIdAndUserIdForUpdate(factorId, user.getId())
                .orElseThrow(() -> new IllegalArgumentException("Enrolment not found."));

        if (!MfaFactor.TYPE_TOTP.equals(factor.getFactorType())) {
            throw new IllegalArgumentException("That factor is not an authenticator enrolment.");
        }
        if (!MfaFactor.STATUS_PENDING.equals(factor.getStatus())) {
            throw new IllegalStateException("That enrolment has already been completed or removed.");
        }
        if (factor.getCreatedAt() != null
                && factor.getCreatedAt().isBefore(LocalDateTime.now().minusMinutes(ENROLMENT_MINUTES))) {
            throw new IllegalStateException("This enrolment has expired. Start again to get a new QR code.");
        }

        String secret = mfaSecretCipher.decrypt(factor.getSecretCiphertext(), user.getId());
        Optional<Long> step = totpService.verify(secret, code, factor.getLastTotpStep());
        if (step.isEmpty()) {
            throw new IllegalArgumentException("That code is not valid. Check your device's clock and try again.");
        }

        factor.setStatus(MfaFactor.STATUS_ACTIVE);
        factor.setLastTotpStep(step.get());
        factor.setLastUsedAt(LocalDateTime.now());
        mfaFactorRepository.save(factor);

        // Promote the stored policy now that a second factor genuinely exists.
        mfaPolicyService.syncPolicy(user);
        return factor;
    }

    /**
     * Verifies a TOTP code during login.
     *
     * @return true when accepted. On success the consumed time-step is recorded so the
     *         same code cannot be replayed inside the drift window.
     */
    @Transactional
    public boolean verifyTotp(User user, Long factorId, String code) {
        Optional<MfaFactor> found = mfaFactorRepository.findByIdAndUserIdForUpdate(factorId, user.getId());
        if (found.isEmpty()) {
            return false;
        }
        MfaFactor factor = found.get();
        if (!MfaFactor.TYPE_TOTP.equals(factor.getFactorType()) || !factor.isActive()) {
            return false;
        }

        String secret = mfaSecretCipher.decrypt(factor.getSecretCiphertext(), user.getId());
        Optional<Long> step = totpService.verify(secret, code, factor.getLastTotpStep());
        if (step.isEmpty()) {
            return false;
        }

        factor.setLastTotpStep(step.get());
        factor.setLastUsedAt(LocalDateTime.now());
        mfaFactorRepository.save(factor);
        return true;
    }

    /** @return the user's active TOTP factor, if any. */
    public Optional<MfaFactor> activeTotpFactor(User user) {
        return mfaFactorRepository.findFirstByUserIdAndFactorTypeAndStatus(
                user.getId(), MfaFactor.TYPE_TOTP, MfaFactor.STATUS_ACTIVE);
    }
    /** @return true when the account has at least one usable passkey. */
    public boolean hasActivePasskey(User user) {
        return !mfaFactorRepository.findByUserIdAndFactorTypeAndStatus(
                user.getId(), MfaFactor.TYPE_WEBAUTHN, MfaFactor.STATUS_ACTIVE).isEmpty();
    }

    /**
     * Disables a factor.
     *
     * @throws IllegalStateException when this would leave the account with no way to
     *         satisfy MFA
     */
    @Transactional
    public MfaFactor disable(User user, Long factorId) {
        MfaFactor factor = mfaFactorRepository.findByIdAndUserIdForUpdate(factorId, user.getId())
                .orElseThrow(() -> new IllegalArgumentException("Factor not found."));

        mfaPolicyService.assertCanRemove(user, factor);

        factor.setStatus(MfaFactor.STATUS_DISABLED);
        factor.setDisabledAt(LocalDateTime.now());
        // Drop the key material: a disabled factor retains nothing that could be
        // decrypted or replayed. Covers TOTP secrets and passkey credentials alike --
        // the invariant lives on the entity so this path and the credential adapter
        // cannot diverge again.
        factor.clearKeyMaterial();
        mfaFactorRepository.save(factor);

        mfaPolicyService.syncPolicy(user);
        return factor;
    }

    private static String defaultLabel() {
        return "Authenticator app";
    }

    /** Result of starting an enrolment; the secret is shown once and never re-read. */
    public record TotpEnrolment(Long factorId, String secret, String otpauthUri, int expiresInMinutes) {
    }
}

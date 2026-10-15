package com.roadwise.backend.controller;

import com.roadwise.backend.dto.MfaConfirmRequestDto;
import com.roadwise.backend.dto.MfaFactorResponseDto;
import com.roadwise.backend.dto.TotpEnrolRequestDto;
import com.roadwise.backend.dto.PasskeyRegistrationRequestDto;
import com.roadwise.backend.model.MfaFactor;
import com.roadwise.backend.model.User;
import com.roadwise.backend.service.MfaFactorService;
import com.roadwise.backend.service.MfaPolicyService;
import com.roadwise.backend.service.MfaRecoveryCodeService;
import com.roadwise.backend.service.ActivityLogService;
import com.roadwise.backend.service.PasskeyService;
import com.roadwise.backend.util.SecurityUtils;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Self-service management of the signed-in user's own MFA enrolment.
 *
 * <p>Every route here acts on the caller's own account and derives the subject from
 * the authenticated principal, never from the request. There is no "manage another
 * user's factors" surface, so there is no id to tamper with.
 *
 * <p>These are the only MFA routes that require a full session. The login ceremony
 * ({@code /login}, {@code /mfa/select}, {@code /verify-mfa}) is necessarily reachable
 * before a session exists and authenticates by challenge token instead.
 */
@RestController
@RequestMapping("/api/auth/mfa")
public class MfaController {

    private final MfaPolicyService mfaPolicyService;
    private final MfaFactorService mfaFactorService;
    private final MfaRecoveryCodeService mfaRecoveryCodeService;
    private final ActivityLogService activityLogService;
    private final PasskeyService passkeyService;

    public MfaController(MfaPolicyService mfaPolicyService,
                         MfaFactorService mfaFactorService,
                         MfaRecoveryCodeService mfaRecoveryCodeService,
                         ActivityLogService activityLogService,
                         PasskeyService passkeyService) {
        this.mfaPolicyService = mfaPolicyService;
        this.mfaFactorService = mfaFactorService;
        this.mfaRecoveryCodeService = mfaRecoveryCodeService;
        this.activityLogService = activityLogService;
        this.passkeyService = passkeyService;
    }

    // ==========================================
    // PASSKEY (WEBAUTHN) ENROLMENT
    // ==========================================

    /**
     * First half of passkey enrolment: issues creation options bound to a one-time
     * ceremony key. The key is shown to this client once, stored only as a digest, and
     * must come back with the authenticator's attestation to complete enrolment.
     *
     * <p>Under the factor-management path, so it inherits both protections: an
     * authenticated session and a recent password entry (the step-up interceptor).
     */
    @PostMapping("/factors/passkeys/options")
    public ResponseEntity<?> beginPasskeyRegistration() {
        User user = SecurityUtils.getCurrentUser();
        if (!passkeyService.isConfigured()) {
            return ResponseEntity.status(503).body(Map.of(
                    "error", "Passkeys are not available on this server."));
        }
        try {
            return ResponseEntity.ok(passkeyService.beginRegistration(
                    user, SecurityContextHolder.getContext().getAuthentication()));
        } catch (IllegalStateException | IllegalArgumentException badRequest) {
            return ResponseEntity.badRequest().body(Map.of("error", badRequest.getMessage()));
        }
    }

    /**
     * Second half: verifies the attestation against the ceremony issued above and
     * enrols the credential as a WEBAUTHN factor. A failure consumes the ceremony (the
     * outcome is committed, never rolled back) so a rejected response cannot be retried
     * against the same key.
     */
    @PostMapping("/factors/passkeys")
    public ResponseEntity<?> completePasskeyRegistration(
            @Valid @RequestBody PasskeyRegistrationRequestDto dto,
            HttpServletRequest request) {
        User user = SecurityUtils.getCurrentUser();
        if (!passkeyService.isConfigured()) {
            return ResponseEntity.status(503).body(Map.of(
                    "error", "Passkeys are not available on this server."));
        }
        String formattedUserId = String.format("#USR-%04d", user.getId());
        PasskeyService.RegistrationResult result = passkeyService.completeRegistration(
                user, dto.getCeremonyKey(), dto.getCredential(), dto.getLabel());
        if (result.success()) {
            activityLogService.log(
                    user, "AUTH", "AUTH_PASSKEY_REGISTERED", formattedUserId,
                    "Passkey registered.", "SUCCESS", request);
            Map<String, Object> response = new HashMap<>();
            // Defensive: success implies the repository persisted the factor. If the
            // re-read somehow found nothing, say so plainly rather than inventing one.
            response.put("factor", result.factor() != null
                    ? MfaFactorResponseDto.from(result.factor())
                    : Map.of("registered", true));
            response.put("policy", mfaPolicyService.effectivePolicy(user));
            return ResponseEntity.status(201).body(response);
        }
        activityLogService.log(
                user, "AUTH", "AUTH_PASSKEY_REGISTRATION_FAILED", formattedUserId,
                "Passkey registration rejected: " + result.code() + ".", "FAILED", request);
        return ResponseEntity.badRequest().body(Map.of(
                "error", "That passkey could not be verified.",
                "code", result.code()));
    }

    /** Everything the account-settings screen needs to render the MFA panel. */
    @GetMapping("/overview")
    public ResponseEntity<?> overview() {
        User user = SecurityUtils.getCurrentUser();
        if (user == null) {
            return unauthorized();
        }

        List<MfaFactorResponseDto> enrolled = mfaFactorService.list(user).stream()
                .map(MfaFactorResponseDto::from)
                .toList();

        Map<String, Object> body = new HashMap<>();
        body.put("policy", mfaPolicyService.effectivePolicy(user));
        body.put("offeredFactors", mfaPolicyService.offeredFactorTypes(user));
        body.put("enrolledFactors", enrolled);
        body.put("secondFactorEnabled", mfaPolicyService.hasSecondFactor(user));
        body.put("totpAvailable", mfaFactorService.isTotpEnrolmentAvailable());
        body.put("recoveryCodesRemaining", mfaRecoveryCodeService.remaining(user));
        body.put("email", maskEmail(user.getEmail()));
        return ResponseEntity.ok(body);
    }

    /**
     * Starts authenticator-app enrolment.
     *
     * <p>The secret in the response is the only time it is ever readable. It is shown
     * once, transcribed into the app, and confirmed immediately; nothing re-reads it.
     */
    @PostMapping("/factors/totp")
    public ResponseEntity<?> enrolTotp(@RequestBody(required = false) TotpEnrolRequestDto body) {
        User user = SecurityUtils.getCurrentUser();
        if (user == null) {
            return unauthorized();
        }
        if (!mfaFactorService.isTotpEnrolmentAvailable()) {
            return ResponseEntity.status(503).body(Map.of(
                    "error", "Authenticator-app enrolment is not enabled on this server.",
                    "detail", "mfa.encryption-key (env MFA_ENCRYPTION_KEY) is not configured."));
        }

        try {
            MfaFactorService.TotpEnrolment enrolment =
                    mfaFactorService.enrolTotp(user, body == null ? null : body.getLabel());

            Map<String, Object> response = new HashMap<>();
            response.put("factorId", enrolment.factorId());
            response.put("secret", enrolment.secret());
            response.put("otpauthUri", enrolment.otpauthUri());
            response.put("issuer", MfaFactorService.ISSUER);
            response.put("expiresInMinutes", enrolment.expiresInMinutes());
            response.put("message", "Scan the QR code, then confirm with a code from the app.");
            return ResponseEntity.ok(response);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Completes TOTP enrolment.
     *
     * <p>Recovery codes are issued here if the user has none. That ordering is
     * deliberate: the escape hatch should exist before the user depends on the factor,
     * not after they have lost it.
     */
    @PostMapping("/factors/totp/confirm")
    public ResponseEntity<?> confirmTotp(@Valid @RequestBody MfaConfirmRequestDto body) {
        User user = SecurityUtils.getCurrentUser();
        if (user == null) {
            return unauthorized();
        }

        try {
            MfaFactor factor = mfaFactorService.confirmTotp(user, body.getFactorId(), body.getCode());

            Map<String, Object> response = new HashMap<>();
            response.put("factor", MfaFactorResponseDto.from(factor));
            response.put("policy", mfaPolicyService.effectivePolicy(user));

            List<String> issued = null;
            if (mfaRecoveryCodeService.remaining(user) == 0) {
                issued = mfaRecoveryCodeService.regenerate(user);
            }
            response.put("recoveryCodes", issued);
            response.put("message", issued != null
                    ? "Authenticator app enrolled. Save your recovery codes now - they will not be shown again."
                    : "Authenticator app enrolled.");
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }

    /** Removes a factor, refusing when it would leave no way to satisfy MFA. */
    @DeleteMapping("/factors/{factorId}")
    public ResponseEntity<?> removeFactor(@PathVariable Long factorId) {
        User user = SecurityUtils.getCurrentUser();
        if (user == null) {
            return unauthorized();
        }

        try {
            MfaFactor factor = mfaFactorService.disable(user, factorId);

            Map<String, Object> response = new HashMap<>();
            response.put("factor", MfaFactorResponseDto.from(factor));
            response.put("policy", mfaPolicyService.effectivePolicy(user));
            response.put("offeredFactors", mfaPolicyService.offeredFactorTypes(user));
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Replaces the recovery-code set.
     *
     * <p>Destructive by design: the previous codes stop working immediately, so a user
     * who is uneasy about a leaked sheet can invalidate it without changing anything
     * else.
     */
    @PostMapping("/recovery-codes")
    public ResponseEntity<?> regenerateRecoveryCodes() {
        User user = SecurityUtils.getCurrentUser();
        if (user == null) {
            return unauthorized();
        }

        List<String> codes = mfaRecoveryCodeService.regenerate(user);

        Map<String, Object> response = new HashMap<>();
        response.put("recoveryCodes", codes);
        response.put("count", codes.size());
        response.put("message", "Save these now. Each code works once, and they will not be shown again.");
        return ResponseEntity.ok(response);
    }

    private static ResponseEntity<?> unauthorized() {
        return ResponseEntity.status(401)
                .body(Map.of("error", "Authentication required."));
    }

    /**
     * Reduces an address to {@code j***e@example.com} for display.
     *
     * <p>The user already knows their own address, so this is not access control. It
     * keeps the full address out of screenshots, shoulder-surfed screens and support
     * transcripts, which is where confirmation prompts tend to end up.
     */
    private static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        String local = email.substring(0, at);
        String domain = email.substring(at);
        if (local.length() <= 2) {
            return local.charAt(0) + "***" + domain;
        }
        return local.charAt(0) + "***" + local.charAt(local.length() - 1) + domain;
    }
}

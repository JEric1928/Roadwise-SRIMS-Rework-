package com.roadwise.backend.controller;

import com.roadwise.backend.config.JwtTokenProvider;
import com.roadwise.backend.dto.*;
import com.roadwise.backend.model.*;
import com.roadwise.backend.repository.*;
import com.roadwise.backend.service.ActivityLogService;
import com.roadwise.backend.service.EmailService;
import com.roadwise.backend.service.NotificationService;
import com.roadwise.backend.service.RateLimitService;
import com.roadwise.backend.service.MfaFactorService;
import com.roadwise.backend.service.MfaPolicyService;
import com.roadwise.backend.service.MfaRecoveryCodeService;
import com.roadwise.backend.service.PasskeyService;
import com.roadwise.backend.service.SessionRevocationService;
import com.roadwise.backend.service.TurnstileService;
import com.roadwise.backend.util.PasswordPolicy;
import com.roadwise.backend.util.SecurityUtils;
import com.roadwise.backend.util.TokenHashes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;


import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SystemSettingsRepository systemSettingsRepository;

    @Autowired
    private EmailService emailService;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private ActivityLogService activityLogService;

    @Autowired
    private MfaChallengeRepository mfaChallengeRepository;

    @Autowired
    private MfaPolicyService mfaPolicyService;

    @Autowired
    private MfaFactorService mfaFactorService;

    @Autowired
    private MfaRecoveryCodeService mfaRecoveryCodeService;


    @Autowired
    private PasskeyService passkeyService;

    @Autowired
    private TurnstileService turnstileService;

    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private RateLimitService rateLimitService;

    @Autowired
    private SessionRevocationService sessionRevocationService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Value("${jwt.refresh-token-expiration-ms:604800000}") // 7 days default
    private long refreshTokenExpirationMs;

    // Absolute login-session lifetime; rotation never extends it.
    @Value("${jwt.session-absolute-expiration-ms:28800000}") // 8 hours default
    private long sessionAbsoluteExpirationMs;

    // Inactivity window enforced when a refresh is attempted.
    @Value("${jwt.session-idle-expiration-ms:1800000}") // 30 minutes default
    private long sessionIdleExpirationMs;

    private final SecureRandom secureRandom = new SecureRandom();

    private String dummyPasswordHash;

    @PostConstruct
    void initialiseDummyPasswordHash() {
        this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    // Failed-attempt counters age out after this window instead of blocking forever.
    private static final Duration RESET_REQUEST_COOLDOWN = Duration.ofSeconds(60);
    private static final int RESET_REQUEST_MAX_PER_WINDOW = 5;

    /** Lifetime of a login challenge, and the window a recovery code must be used in. */
    private static final int CHALLENGE_MINUTES = 5;

    private Long getAdminId() {
        return userRepository.findFirstByRoleInIgnoreCase(List.of("cpdo admin", "admin"))
                .map(User::getId)
                .orElse(1L);
    }

    /**
     * SHA-256 hash of a raw bearer token before persistence. MD5 was previously
     * used and is unsuitable even for high-entropy tokens; switching invalidates
     * any outstanding refresh tokens (a one-time re-login, acceptable).
     */
    static String hashToken(String rawToken) {
        // One canonical digest, shared with ceremony keys: the raw bearer value is shown
        // once and only this is stored.
        return TokenHashes.sha256Hex(rawToken);
    }

    private boolean checkRateLimit(String keyIdentifier) {
        return !rateLimitService.isBlocked(keyIdentifier);
    }

    private void recordFailedAttempt(String keyIdentifier) {
        rateLimitService.recordFailure(keyIdentifier);
    }

    private boolean isResetRequestThrottled(String keyIdentifier) {
        return rateLimitService.isRequestThrottled(
                keyIdentifier, RESET_REQUEST_COOLDOWN, RESET_REQUEST_MAX_PER_WINDOW);
    }

    private void recordResetRequest(String keyIdentifier) {
        rateLimitService.recordRequest(keyIdentifier);
    }

    private void clearRateLimit(String keyIdentifier) {
        rateLimitService.clear(keyIdentifier);
    }

    private LocalDateTime effectiveRefreshExpiry(LocalDateTime now, LocalDateTime sessionExpiresAt) {
        LocalDateTime configured = now.plusNanos(refreshTokenExpirationMs * 1_000_000L);
        if (sessionExpiresAt != null && sessionExpiresAt.isBefore(configured)) {
            return sessionExpiresAt;
        }
        return configured;
    }

    private void revokeAllRefreshTokens(User user) {
        List<RefreshToken> activeTokens = refreshTokenRepository.findByUserAndRevokedFalse(user);
        for (RefreshToken token : activeTokens) {
            token.setRevoked(true);
        }
        refreshTokenRepository.saveAll(activeTokens);
    }

    // ==========================================
    // 1. LOGIN & MFA DISPATCH
    // ==========================================
    @PostMapping("/login")
    @Transactional
    public ResponseEntity<?> login(
            @Valid @RequestBody LoginRequestDto loginDto,
            HttpServletRequest request) {
        String username = loginDto.getUsername();
        String password = loginDto.getPassword();
        String clientIp = request.getRemoteAddr();

        if (!checkRateLimit("IP:" + clientIp) || !checkRateLimit("USER:" + username)) {
            return ResponseEntity.status(429)
                    .header("Retry-After", "60")
                    .body(Map.of("error", "Too many attempts. Account or IP temporarily rate-limited."));
        }

        // Bot check before anything is looked up. It runs after the rate limiters, so a
        // flood of token-less requests is still throttled per IP, and before the user
        // lookup, so a refused widget costs no database work and cannot be used to probe
        // which usernames exist. Only the IP counter is charged: charging the account
        // counter here would let anyone lock a known account out by posting junk tokens,
        // which is the same denial of service the lockout policy is meant to prevent.
        // Failures are refusals in every direction -- see TurnstileService's class docs.
        TurnstileService.Result turnstile = turnstileService.verify(
                loginDto.getTurnstileToken(), clientIp);
        if (!turnstile.success()) {
            recordFailedAttempt("IP:" + clientIp);
            activityLogService.log(
                    null, "AUTH", "AUTH_BOT_CHECK_FAILED", username,
                    "Login blocked: Turnstile verification failed (" + turnstile.reason() + ").",
                    "FAILED", request);
            return ResponseEntity.status(403).body(Map.of(
                    "error", "Security check failed. Please complete the verification and try again.",
                    "type", "TURNSTILE_FAILED"));
        }

        Optional<User> userOpt = userRepository.findByUsername(username)
                .or(() -> userRepository.findByEmail(username));

        if (userOpt.isEmpty()) {
            recordFailedAttempt("IP:" + clientIp);
            recordFailedAttempt("USER:" + username);
            activityLogService.log(
                    null, "AUTH", "AUTH_LOGIN_FAILED", username,
                    "Failed login attempt for unknown account: '" + username + "'",
                    "FAILED", request);
            return ResponseEntity.status(401).body(Map.of("error", "Invalid username or password."));
        }

        User user = userOpt.get();
        String formattedUserId = String.format("#USR-%04d", user.getId());

        if (user.getPasswordChangeRequired() && user.getTempPasswordExpiresAt() != null
                && LocalDateTime.now().isAfter(user.getTempPasswordExpiresAt())) {
            activityLogService.log(
                    null, "AUTH", "AUTH_TEMP_PASSWORD_EXPIRED", formattedUserId,
                    "Login attempt with expired temporary password.", "WARNING", request);
            return ResponseEntity.status(401).body(Map.of("error",
                    "Temporary password has expired. Please request a password reset or contact CPDO Admin."));
        }

        boolean passwordMatches;
        if (user.getPassword().startsWith("$2a$")
                || user.getPassword().startsWith("$2b$")
                || user.getPassword().startsWith("$2y$")) {
            passwordMatches = passwordEncoder.matches(password, user.getPassword());
        } else {
            passwordMatches = MessageDigest.isEqual(
                    user.getPassword().getBytes(StandardCharsets.UTF_8),
                    password.getBytes(StandardCharsets.UTF_8));
            if (passwordMatches) {
                user.setPassword(passwordEncoder.encode(password));
            }
        }

        if (!passwordMatches) {
            recordFailedAttempt("IP:" + clientIp);
            recordFailedAttempt("USER:" + username);
            activityLogService.log(
                    null, "AUTH", "AUTH_LOGIN_FAILED", formattedUserId,
                    "Failed password verification for '" + username + "'", "FAILED", request);
            return ResponseEntity.status(401).body(Map.of("error", "Invalid username or password."));
        }

        // Persist any legacy-plaintext→BCrypt upgrade. Lockout counters and rate
        // limits are only cleared after MFA succeeds, so possession of a stolen
        // password alone cannot reset brute-force protections.
        userRepository.save(user);

        if (user.getPasswordChangeRequired() && user.getTempPasswordExpiresAt() != null
                && LocalDateTime.now().isAfter(user.getTempPasswordExpiresAt())) {
            activityLogService.log(
                    null, "AUTH", "AUTH_TEMP_PASSWORD_EXPIRED", formattedUserId,
                    "Login attempt with expired temporary password.", "WARNING", request);
            return ResponseEntity.status(401).body(Map.of("error",
                    "Temporary password has expired. Please request a password reset or contact CPDO Admin."));
        }

        SystemSettings settings = systemSettingsRepository.findById(1L).orElse(null);
        if (settings != null && settings.isMaintenanceMode()) {
            if (!user.getRole().equalsIgnoreCase("Admin")
                    && !user.getRole().equalsIgnoreCase("CPDO Admin")) {
                activityLogService.log(
                        user, "AUTH", "AUTH_MAINTENANCE_BLOCKED", formattedUserId,
                        "Login during maintenance mode.", "WARNING", request);
                return ResponseEntity.status(403).body(Map.of(
                        "error", "System is down for maintenance. Please try again later.",
                        "type", "MAINTENANCE_MODE"));
            }
        }

        if ("Suspended".equalsIgnoreCase(user.getStatus())) {
            activityLogService.log(
                    user, "AUTH", "AUTH_SUSPENDED_BLOCKED", formattedUserId,
                    "Suspended account login attempt.", "FAILED", request);
            return ResponseEntity.status(403)
                    .body(Map.of("error", "Your account is currently Suspended. Please contact CPDO."));
        }

        if ("Deactivated".equalsIgnoreCase(user.getStatus())) {
            activityLogService.log(
                    user, "AUTH", "AUTH_DEACTIVATED_BLOCKED", formattedUserId,
                    "Deactivated account login attempt.", "FAILED", request);
            return ResponseEntity.status(403)
                    .body(Map.of("error", "Your account has been Deactivated. Access revoked."));
        }

        if (user.getEmail() == null || user.getEmail().isEmpty()) {
            return ResponseEntity.status(403).body(Map.of(
                    "error", "No official email is linked to this account. Cannot proceed with MFA. Contact Admin."));
        }

        // MFA resend cooldown: reject a new code while a recent challenge is
        // still fresh, so OTP email cannot be spammed.
        // MFA resend cooldown: reject a new code while a recent challenge is still
        // fresh, so OTP email cannot be used to spam the account owner.
        Optional<MfaChallenge> existingChallenge =
                mfaChallengeRepository.findTopByUserAndUsedFalseOrderByCreatedAtDesc(user);
        if (existingChallenge.isPresent()
                && existingChallenge.get().getCreatedAt() != null
                && existingChallenge.get().getCreatedAt().isAfter(LocalDateTime.now().minusSeconds(60))) {
            return ResponseEntity.status(429)
                    .header("Retry-After", "60")
                    .body(Map.of(
                            "error", "A verification code was just sent. Please wait a minute before requesting another."));
        }
        existingChallenge.ifPresent(c -> {
            c.setUsed(true);
            mfaChallengeRepository.save(c);
        });

        List<String> offered = mfaPolicyService.offeredFactorTypes(user);

        // The challenge is bound to a token this server mints. The user id is
        // deliberately no longer returned: it was the request field that let
        // /verify-mfa be driven entirely by the caller.
        byte[] tokenBytes = new byte[32];
        secureRandom.nextBytes(tokenBytes);
        String rawMfaToken = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);

        MfaChallenge challenge = MfaChallenge.forToken(
                user, hashToken(rawMfaToken), LocalDateTime.now().plusMinutes(CHALLENGE_MINUTES));
        mfaChallengeRepository.save(challenge);

        // Recovery codes are a break-glass alternative rather than a first-line choice,
        // so on their own they do not force a picker. Any second real factor does.
        boolean selectionRequired = offered.stream()
                .filter(type -> !MfaPolicyService.RECOVERY_CODE.equals(type))
                .count() > 1;

        if (!selectionRequired && offered.contains(MfaFactor.TYPE_EMAIL_OTP)) {
            // One option and it is email: settle the factor now and dispatch, which
            // keeps the existing one-step flow for every account that has not enrolled
            // anything else.
            issueEmailOtp(user, challenge);
        }

        activityLogService.log(
                user, "AUTH", "AUTH_MFA_REQUESTED", formattedUserId,
                "Credentials validated. Awaiting MFA verification.", "SUCCESS", request);

        Map<String, Object> responseData = new HashMap<>();
        responseData.put("mfaRequired", true);
        responseData.put("mfaToken", rawMfaToken);
        responseData.put("factorSelectionRequired", selectionRequired);
        responseData.put("factors", offered.stream().map(this::describeFactor).toList());
        responseData.put("expiresInSeconds", CHALLENGE_MINUTES * 60);
        responseData.put("message", selectionRequired
                ? "Choose how you would like to verify."
                : "A 6-digit code has been sent to your email.");

        return ResponseEntity.ok(responseData);
    }

    // ==========================================
    // 2. VERIFY MFA & ISSUE JWT TOKENS
    // ==========================================
    @PostMapping("/verify-mfa")
    @Transactional
    public ResponseEntity<?> verifyMfa(
            @Valid @RequestBody MfaVerifyRequestDto verifyDto,
            HttpServletRequest request) {
        Optional<MfaChallenge> challengeOpt =
                mfaChallengeRepository.findByChallengeTokenHash(hashToken(verifyDto.getMfaToken()));
        if (challengeOpt.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of(
                    "error", "This verification session is no longer valid. Please log in again."));
        }
        MfaChallenge challenge = challengeOpt.get();
        if (challenge.isUsed()) {
            return ResponseEntity.status(401).body(Map.of(
                    "error", "This verification session has already been used. Please log in again."));
        }

        User user = challenge.getUser();
        String formattedUserId = String.format("#USR-%04d", user.getId());

        if (LocalDateTime.now().isAfter(challenge.getExpiresAt())) {
            challenge.setUsed(true);
            mfaChallengeRepository.save(challenge);
            return ResponseEntity.status(400)
                    .body(Map.of("error", "Verification code expired. Please log in again."));
        }

        if (!challenge.isFactorSelected()) {
            return ResponseEntity.status(400).body(Map.of(
                    "error", "Choose a verification method first.",
                    "requiresFactorSelection", true));
        }

        if (challenge.getAttempts() >= challenge.getMaxAttempts()) {
            challenge.setUsed(true);
            mfaChallengeRepository.save(challenge);
            return ResponseEntity.status(429)
                    .body(Map.of("error", "Maximum verification attempts exceeded. Please log in again."));
        }

        if (!verifyChallengeFactor(user, challenge, verifyDto)) {
            challenge.setAttempts(challenge.getAttempts() + 1);
            mfaChallengeRepository.save(challenge);
            activityLogService.log(
                    user, "AUTH", "AUTH_MFA_FAILED", formattedUserId,
                    "Invalid MFA code submitted for factor " + challenge.getFactorType() + ".", "FAILED", request);
            return ResponseEntity.status(401)
                    .body(Map.of("error", "Invalid verification code. Please try again."));
        }

        challenge.setUsed(true);
        mfaChallengeRepository.save(challenge);

        return completeLogin(user, challenge.getFactorType(), request);
    }

    // ==========================================
    // 2b. CHOOSE WHICH ENROLLED FACTOR TO USE
    // ==========================================
    /**
     * Points the in-flight challenge at one of the account's enrolled factors.
     *
     * <p>This doubles as "resend code": re-selecting email OTP mints a fresh OTP onto
     * the same challenge.
     *
     * <p>Selections are charged against the same ceiling as wrong codes. Without that,
     * a caller holding a valid password could re-select indefinitely to reset the
     * counter and brute-force the 6-digit space, which would be strictly worse than
     * the behaviour it replaces.
     */
    @PostMapping("/mfa/select")
    @Transactional
    public ResponseEntity<?> selectMfaFactor(
            @Valid @RequestBody MfaSelectRequestDto selectDto,
            HttpServletRequest request) {
        Optional<MfaChallenge> challengeOpt =
                mfaChallengeRepository.findByChallengeTokenHash(hashToken(selectDto.getMfaToken()));
        if (challengeOpt.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of(
                    "error", "This verification session is no longer valid. Please log in again."));
        }
        MfaChallenge challenge = challengeOpt.get();
        if (challenge.isUsed()) {
            return ResponseEntity.status(401)
                    .body(Map.of("error", "This verification session has already been used."));
        }
        if (LocalDateTime.now().isAfter(challenge.getExpiresAt())) {
            challenge.setUsed(true);
            mfaChallengeRepository.save(challenge);
            return ResponseEntity.status(400)
                    .body(Map.of("error", "Verification session expired. Please log in again."));
        }
        if (challenge.getAttempts() >= challenge.getMaxAttempts()) {
            challenge.setUsed(true);
            mfaChallengeRepository.save(challenge);
            return ResponseEntity.status(429)
                    .body(Map.of("error", "Maximum verification attempts exceeded. Please log in again."));
        }

        User user = challenge.getUser();
        String requested = selectDto.getFactorType().trim().toUpperCase(Locale.ROOT);

        // The body expresses a preference. Whether the account actually holds that
        // factor is decided here, from enrolment, never from anything the client sent.
        if (!mfaPolicyService.isFactorAllowed(user, requested)) {
            return ResponseEntity.status(403).body(Map.of(
                    "error", "That verification method is not available for this account."));
        }

        challenge.setAttempts(challenge.getAttempts() + 1);

        // Which factor this challenge answers for. Without this the verification step
        // cannot tell a TOTP code from a recovery code (only email OTP set it, via
        // issueEmailOtp), so every non-email factor silently failed verification.
        challenge.setFactorType(requested);
        Map<String, Object> responseData = new HashMap<>();
        responseData.put("factorType", requested);

        if (MfaFactor.TYPE_EMAIL_OTP.equals(requested)) {
            issueEmailOtp(user, challenge);
            responseData.put("message", "A 6-digit code has been sent to your email.");
        } else if (MfaFactor.TYPE_TOTP.equals(requested)) {
            Long factorId = mfaFactorService.activeTotpFactor(user).map(MfaFactor::getId).orElse(null);
            if (factorId == null) {
                return ResponseEntity.status(403)
                        .body(Map.of("error", "No authenticator app is enrolled on this account."));
            }
            challenge.setFactorId(factorId);
            mfaChallengeRepository.save(challenge);
            responseData.put("message", "Enter the 6-digit code from your authenticator app.");
        } else if (MfaFactor.TYPE_WEBAUTHN.equals(requested)) {
            if (!passkeyService.isConfigured()) {
                return ResponseEntity.status(503).body(Map.of(
                        "error", "Passkeys are not available on this server."));
            }
            if (!mfaFactorService.hasActivePasskey(user)) {
                return ResponseEntity.status(403)
                        .body(Map.of("error", "No passkey is enrolled on this account."));
            }
            // The assertion ceremony is bound to this challenge, not to a user id: the
            // key returned here must be presented back with the authenticator's response,
            // and the ceremony store enforces the binding under a row lock. The login
            // token alone cannot be replayed against another challenge.
            challenge.setFactorId(null);
            mfaChallengeRepository.save(challenge);
            try {
                responseData.put("passkey", passkeyService.beginAuthentication(user, challenge.getId()));
            } catch (IllegalStateException unavailable) {
                return ResponseEntity.status(503).body(Map.of("error", unavailable.getMessage()));
            }
            responseData.put("message", "Use your passkey to continue.");
        } else if (MfaPolicyService.RECOVERY_CODE.equals(requested)) {
            // Recovery codes are checked against stored hashes, so there is no factor row.
            challenge.setFactorId(null);
            mfaChallengeRepository.save(challenge);
            responseData.put("message", "Enter one of your recovery codes.");
        } else {
            // Reachable for a type that MfaPolicyService offers but that has no
            // verification path yet. Failing loudly is deliberate: an offered factor must
            // never fall through to another proof's instructions (the original bug this
            // replaced), and verifyChallengeFactor() must never silently reject a type
            // nobody could ever satisfy.
            return ResponseEntity.status(501).body(Map.of(
                    "error", "That verification method is not available yet.",
                    "factorType", requested));
        }

        activityLogService.log(
                user, "AUTH", "AUTH_MFA_FACTOR_SELECTED", String.format("#USR-%04d", user.getId()),
                "MFA factor selected: " + requested, "SUCCESS", request);

        return ResponseEntity.ok(responseData);
    }

    /**
     * Checks a submitted code against the factor the challenge is bound to.
     *
     * <p>Both the factor and the user come from the server-side challenge row, so a
     * request cannot redirect verification at an account or factor of its choosing.
     */
    private boolean verifyChallengeFactor(User user, MfaChallenge challenge, MfaVerifyRequestDto dto) {
        String factorType = challenge.getFactorType();
        if (MfaFactor.TYPE_EMAIL_OTP.equals(factorType)) {
            String code = dto.getCode();
            return challenge.getOtpCodeHash() != null
                    && passwordEncoder.matches(code == null ? "" : code.trim(), challenge.getOtpCodeHash());
        }
        if (MfaFactor.TYPE_TOTP.equals(factorType)) {
            return challenge.getFactorId() != null
                    && mfaFactorService.verifyTotp(user, challenge.getFactorId(), dto.getCode());
        }
        if (MfaFactor.TYPE_WEBAUTHN.equals(factorType)) {
            // A failed assertion must consume its ceremony (a committed FAILED_* outcome,
            // not a rollback that would re-open it) and count as a used attempt below,
            // exactly like a wrong code. IllegalStateException means passkeys are not
            // configured on this server: a refusal, never a pass.
            try {
                return passkeyService.verifyAssertion(
                                user, challenge, dto.getCeremonyKey(), dto.getAssertion())
                        .success();
            } catch (IllegalStateException unavailable) {
                return false;
            }
        }
        if (MfaPolicyService.RECOVERY_CODE.equals(factorType)) {
            return mfaRecoveryCodeService.consume(user, dto.getCode());
        }
        return false;
    }

    /**
     * Mints an email OTP onto a challenge and dispatches it.
     *
     * <p>Pre-existing gap preserved rather than silently widened: EmailService is
     * fire-and-forget and swallows its own failures, so this method cannot distinguish
     * "delivered" from "skipped because no API key is configured". Login therefore
     * reports that a code was sent on the strength of an intent, not a receipt.
     */
    private void issueEmailOtp(User user, MfaChallenge challenge) {
        String rawOtp = String.format("%06d", secureRandom.nextInt(1000000));
        challenge.setOtpCodeHash(passwordEncoder.encode(rawOtp));
        challenge.setFactorType(MfaFactor.TYPE_EMAIL_OTP);
        challenge.setFactorId(null);
        mfaChallengeRepository.save(challenge);

        String subject = "RoadWise - Login Verification Code";
        String body = "Hello " + user.getFirstName() + ",\n\n" +
                "Your Multi-Factor Authentication (MFA) code is: " + rawOtp + "\n\n" +
                "This code will expire in " + CHALLENGE_MINUTES + " minutes. Do not share this code with anyone.\n\n" +
                "If you did not attempt to log in, please contact the CPDO Administrator immediately.";

        try {
            emailService.sendEmail(user.getEmail(), subject, body);
        } catch (Exception e) {
            System.err.println(">>> [WARN] SMTP Delivery failed: " + e.getMessage());
        }
    }

    /** Human-readable label for a factor type offered at the picker. */
    private MfaFactorOptionDto describeFactor(String factorType) {
        return switch (factorType) {
            case MfaFactor.TYPE_EMAIL_OTP -> new MfaFactorOptionDto(factorType, "Email code");
            case MfaFactor.TYPE_TOTP -> new MfaFactorOptionDto(factorType, "Authenticator app");
            case MfaFactor.TYPE_WEBAUTHN -> new MfaFactorOptionDto(factorType, "Passkey");
            case MfaPolicyService.RECOVERY_CODE -> new MfaFactorOptionDto(factorType, "Recovery code");
            default -> new MfaFactorOptionDto(factorType, factorType);
        };
    }

    /**
     * Issues the session once every factor requirement is satisfied.
     *
     * <p>Also the only place rate limits are cleared.
     * Possession of a stolen password must not reset brute-force protections; only
     * completing MFA does.
     */
    private ResponseEntity<?> completeLogin(User user, String verifiedFactor, HttpServletRequest request) {
        clearRateLimit("IP:" + request.getRemoteAddr());
        clearRateLimit("USER:" + user.getUsername());

        String accessToken = jwtTokenProvider.generateAccessToken(user);

        String rawRefreshToken = UUID.randomUUID().toString() + "-" + UUID.randomUUID().toString();
        String refreshTokenHash = hashToken(rawRefreshToken);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime sessionExpiresAt = now.plusNanos(sessionAbsoluteExpirationMs * 1_000_000L);
        LocalDateTime refreshExpiresAt = effectiveRefreshExpiry(now, sessionExpiresAt);

        RefreshToken refreshTokenEntity = new RefreshToken(
                user, refreshTokenHash, refreshExpiresAt, request.getRemoteAddr(), sessionExpiresAt);
        // Record when the human actually authenticated. This is what the step-up check
        // reads, and it has to survive rotation -- so it is stored on the session rather
        // than inferred from the token, whose issue time refresh rewrites.
        refreshTokenEntity.setAuthenticatedAt(now);
        refreshTokenRepository.save(refreshTokenEntity);

        activityLogService.log(
                user, "AUTH", "AUTH_LOGIN_SUCCESS", String.format("#USR-%04d", user.getId()),
                "User verified MFA via " + verifiedFactor + " and established active session for " + user.getRole(),
                "SUCCESS", request);

        AuthResponseDto response = new AuthResponseDto();
        response.setAccessToken(accessToken);
        response.setRefreshToken(rawRefreshToken);
        response.setUserId(user.getId());
        response.setUsername(user.getUsername());
        response.setRole(user.getRole());
        response.setFirstName(user.getFirstName());
        response.setMiddleName(user.getMiddleName());
        response.setLastName(user.getLastName());
        response.setEmail(user.getEmail());
        response.setPhoneNumber(user.getPhoneNumber());
        response.setBirthday(user.getBirthday());
        response.setGender(user.getGender());
        response.setProfilePicture(user.getProfilePicture());
        response.setPasswordChangeRequired(user.getPasswordChangeRequired());

        if (user.getBarangay() != null) {
            response.setBarangayId(user.getBarangay().getId());
            response.setBarangayName(user.getBarangay().getBarangayName());
        } else {
            response.setBarangayId(null);
            response.setBarangayName("City Hall Central");
        }

        return ResponseEntity.ok(response);
    }

    // ==========================================
    // 3. REFRESH TOKEN ROTATION
    // ==========================================
    @PostMapping("/refresh")
    @Transactional
    public ResponseEntity<?> refreshToken(
            @Valid @RequestBody RefreshTokenRequestDto refreshDto,
            HttpServletRequest request) {
        String rawToken = refreshDto.getRefreshToken();
        String tokenHash = hashToken(rawToken);

        Optional<RefreshToken> refreshTokenOpt =
                refreshTokenRepository.findWithLockByTokenHash(tokenHash);
        if (refreshTokenOpt.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid refresh token."));
        }

        RefreshToken token = refreshTokenOpt.get();
        User user = token.getUser();

        if (token.isRevoked()) {
            if (token.getReplacedByTokenHash() != null) {
                // A rotated token was replayed: treat the family as compromised
                // and revoke every remaining token plus all access tokens.
                revokeAllRefreshTokens(user);
                sessionRevocationService.revoke(user, "refresh-token reuse detected");
                activityLogService.log(
                        user, "SECURITY", "REFRESH_TOKEN_REUSE_DETECTED",
                        String.format("#USR-%04d", user.getId()),
                        "A previously rotated refresh token was replayed; all sessions were revoked.",
                        "WARNING", request);
            }
            return ResponseEntity.status(401).body(Map.of("error", "Expired or revoked refresh token."));
        }

        if (LocalDateTime.now().isAfter(token.getExpiresAt())) {
            return ResponseEntity.status(401).body(Map.of("error", "Expired or revoked refresh token."));
        }

        if ("Suspended".equalsIgnoreCase(user.getStatus())
                || "Deactivated".equalsIgnoreCase(user.getStatus())) {
            return ResponseEntity.status(403).body(Map.of("error", "Account access revoked."));
        }

        // Credential-change binding: refuse a token issued under an older
        // session version (password change, emergency reset, logout, reuse).
        if (!token.getSessionVersion().equals(user.getSessionVersion())) {
            revokeAllRefreshTokens(user);
            return ResponseEntity.status(401).body(Map.of("error", "Session has been revoked. Please log in again."));
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime sessionExpiresAt = token.getSessionExpiresAt();

        // Absolute lifetime: rotation must not extend the original session.
        if (sessionExpiresAt != null && now.isAfter(sessionExpiresAt)) {
            token.setRevoked(true);
            refreshTokenRepository.save(token);
            return ResponseEntity.status(401).body(Map.of("error", "Session expired. Please log in again."));
        }

        // Idle timeout: reject a refresh when the session has been inactive for
        // longer than the configured window.
        if (token.getLastUsedAt() != null
                && now.isAfter(token.getLastUsedAt().plusNanos(sessionIdleExpirationMs * 1_000_000L))) {
            token.setRevoked(true);
            refreshTokenRepository.save(token);
            return ResponseEntity.status(401)
                    .body(Map.of("error", "Session timed out due to inactivity. Please log in again."));
        }

        token.setRevoked(true);
        // Inherit the session's original authentication time instead of stamping a new
        // one. A rotated token that looked freshly authenticated would let a caller
        // holding only refresh tokens satisfy the step-up check indefinitely.
        // Epoch 0 when the session predates the column: fail-closed, never "recent".
        long sessionAuthTime = token.getAuthenticatedAt() == null
                ? 0L
                : java.time.Instant.now().getEpochSecond()
                        - Duration.between(token.getAuthenticatedAt(), LocalDateTime.now()).getSeconds();
        String newAccessToken = jwtTokenProvider.generateAccessToken(user, sessionAuthTime);

        String newRawRefreshToken = UUID.randomUUID().toString() + "-" + UUID.randomUUID().toString();
        String newRefreshTokenHash = hashToken(newRawRefreshToken);
        LocalDateTime refreshExpiresAt = effectiveRefreshExpiry(now, sessionExpiresAt);

        token.setReplacedByTokenHash(newRefreshTokenHash);
        refreshTokenRepository.save(token);

        RefreshToken newTokenEntity = new RefreshToken(
                user, newRefreshTokenHash, refreshExpiresAt, request.getRemoteAddr(), sessionExpiresAt);
        // Carry the authentication time forward with the rotation.
        newTokenEntity.setAuthenticatedAt(token.getAuthenticatedAt());
        refreshTokenRepository.save(newTokenEntity);

        Map<String, String> tokens = new HashMap<>();
        tokens.put("accessToken", newAccessToken);
        tokens.put("refreshToken", newRawRefreshToken);

        return ResponseEntity.ok(tokens);
    }

    // ==========================================
    // 4. FORGOT PASSWORD REQUEST
    // ==========================================
    @PostMapping("/forgot-password/request")
    @Transactional
    public ResponseEntity<?> requestPasswordReset(
            @Valid @RequestBody PasswordResetRequestDto resetDto,
            HttpServletRequest request) {
        String email = resetDto.getEmail() == null ? "" : resetDto.getEmail().trim();
        String emailThrottleKey = "RESET_EMAIL:" + email.toLowerCase(Locale.ROOT);
        String ipThrottleKey = "RESET_IP:" + request.getRemoteAddr();

        // Throttle BEFORE looking the account up so request timing/response
        // cannot be used to enumerate registered emails.
        if (isResetRequestThrottled(emailThrottleKey) || isResetRequestThrottled(ipThrottleKey)) {
            return ResponseEntity.status(429)
                    .header("Retry-After", String.valueOf(RESET_REQUEST_COOLDOWN.getSeconds()))
                    .body(Map.of(
                            "error", "Too many reset requests. Please wait a minute and try again.",
                            "message", "If this email exists, a recovery code will be sent shortly."));
        }
        recordResetRequest(emailThrottleKey);
        recordResetRequest(ipThrottleKey);

        Optional<User> userOpt = userRepository.findByEmail(email);
        if (userOpt.isEmpty()) {
            return ResponseEntity.ok(Map.of("message", "If this email exists, a recovery code will be sent shortly."));
        }

        User user = userOpt.get();
        String formattedUserId = String.format("#USR-%04d", user.getId());

        String rawOtp = String.format("%06d", secureRandom.nextInt(1000000));
        String otpHash = passwordEncoder.encode(rawOtp);

        passwordResetTokenRepository.findTopByUserAndUsedFalseOrderByCreatedAtDesc(user)
                .ifPresent(p -> {
                    p.setUsed(true);
                    passwordResetTokenRepository.save(p);
                });

        PasswordResetToken resetToken = new PasswordResetToken(
                user, otpHash, LocalDateTime.now().plusMinutes(10));
        passwordResetTokenRepository.save(resetToken);

        String subject = "RoadWise - Password Reset Code";
        String body = "Hello " + user.getFirstName() + ",\n\n" +
                "You requested a password reset for your RoadWise account.\n\n" +
                "Your 6-digit Password Reset Code is: " + rawOtp + "\n\n" +
                "This code will expire in 10 minutes.\n\n" +
                "If you did not request this, please ignore this email and your password will remain unchanged.";

        try {
            emailService.sendEmail(user.getEmail(), subject, body);
        } catch (Exception e) {
            System.err.println(">>> [WARN] Password Reset SMTP Delivery failed: " + e.getMessage());
        }

        notificationService.sendNotification(
                getAdminId(),
                "Support Request",
                user.getRole() + " " + user.getFirstName() + " " + user.getLastName() + " requested a password reset.",
                "SUPPORT"
        );

        activityLogService.log(
                user, "AUTH", "AUTH_PASSWORD_RESET_REQUEST", formattedUserId,
                "Password recovery OTP requested.", "SUCCESS", request);

        // Deliberately identical to the unknown-email response (no userId, no
        // existence signal).
        return ResponseEntity.ok(Map.of("message", "If this email exists, a recovery code will be sent shortly."));
    }

    // ==========================================
    // 5. FORGOT PASSWORD CONFIRM / RESET
    // ==========================================
    @PostMapping("/forgot-password/reset")
    @Transactional
    public ResponseEntity<?> resetPassword(
            @Valid @RequestBody PasswordResetConfirmDto confirmDto,
            HttpServletRequest request) {
        String email = confirmDto.getEmail() == null ? "" : confirmDto.getEmail().trim();
        String submittedOtp = confirmDto.getOtp();
        String newPassword = confirmDto.getNewPassword();

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Invalid recovery request."));

        Optional<PasswordResetToken> tokenOpt =
                passwordResetTokenRepository.findTopByUserAndUsedFalseOrderByCreatedAtDesc(user);
        if (tokenOpt.isEmpty()) {
            return ResponseEntity.status(400)
                    .body(Map.of("error", "No active password reset request. Please request a new code."));
        }

        PasswordResetToken resetToken = tokenOpt.get();

        if (resetToken.getAttempts() >= resetToken.getMaxAttempts()) {
            resetToken.setUsed(true);
            passwordResetTokenRepository.save(resetToken);
            return ResponseEntity.status(429)
                    .header("Retry-After", "600")
                    .body(Map.of("error", "Maximum recovery attempts exceeded. Please request a new code."));
        }

        if (LocalDateTime.now().isAfter(resetToken.getExpiresAt())) {
            resetToken.setUsed(true);
            passwordResetTokenRepository.save(resetToken);
            return ResponseEntity.status(400).body(Map.of("error", "Reset code has expired. Please request a new one."));
        }

        if (!passwordEncoder.matches(submittedOtp, resetToken.getTokenHash())) {
            resetToken.setAttempts(resetToken.getAttempts() + 1);
            boolean exhausted = resetToken.getAttempts() >= resetToken.getMaxAttempts();
            if (exhausted) {
                resetToken.setUsed(true);
            }
            passwordResetTokenRepository.save(resetToken);
            activityLogService.log(
                    user, "AUTH", "AUTH_PASSWORD_RESET_FAILED", String.format("#USR-%04d", user.getId()),
                    "Invalid reset OTP submitted.", "FAILED", request);
            if (exhausted) {
                return ResponseEntity.status(429)
                        .header("Retry-After", "600")
                        .body(Map.of("error", "Maximum recovery attempts exceeded. Please request a new code."));
            }
            return ResponseEntity.status(401).body(Map.of("error", "Invalid recovery code. Please try again."));
        }

        String policyError = PasswordPolicy.validate(newPassword);
        if (policyError != null) {
            return ResponseEntity.badRequest().body(Map.of("error", policyError));
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        user.setPasswordChangeRequired(false);
        user.setTempPasswordExpiresAt(null);
        sessionRevocationService.revoke(user, "password reset");

        resetToken.setUsed(true);
        passwordResetTokenRepository.save(resetToken);

        // Recovery invalidates every existing session.
        revokeAllRefreshTokens(user);
        clearRateLimit("RESET_EMAIL:" + email.toLowerCase(Locale.ROOT));
        clearRateLimit("RESET_IP:" + request.getRemoteAddr());

        activityLogService.log(
                user, "AUTH", "AUTH_PASSWORD_RESET_SUCCESS", String.format("#USR-%04d", user.getId()),
                "Password successfully updated via recovery verification.", "SUCCESS", request);

        return ResponseEntity.ok(Map.of("message", "Password successfully reset! You can now log in."));
    }

    // ==========================================
    // 6. VERIFY SESSION
    // ==========================================
    // ==========================================
    // 6. STEP-UP RE-AUTHENTICATION
    // ==========================================
    /**
     * Re-proves identity for sensitive changes and returns an access token carrying a
     * fresh authentication time.
     *
     * <p>Rate-limited per account, and it deliberately does not touch the brute-force
     * lockout counters or the session version. A wrong password here is still a password
     * guess, so this must not become an unthrottled oracle; equally, succeeding here must
     * not disturb the caller's other sessions or reset account lockout state, because
     * step-up elevates one client rather than re-establishing the account.
     */
    @PostMapping("/step-up")
    @Transactional
    public ResponseEntity<?> stepUp(
            @Valid @RequestBody StepUpRequestDto stepUpDto,
            HttpServletRequest request) {
        User user = SecurityUtils.getCurrentUser();
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Authentication required."));
        }

        String rateLimitKey = "STEPUP:" + user.getId();
        if (!checkRateLimit(rateLimitKey)) {
            return ResponseEntity.status(429)
                    .header("Retry-After", "60")
                    .body(Map.of("error", "Too many attempts. Please wait before trying again."));
        }

        if (!passwordEncoder.matches(stepUpDto.getPassword(), user.getPassword())) {
            recordFailedAttempt(rateLimitKey);
            activityLogService.log(
                    user, "AUTH", "AUTH_STEP_UP_FAILED", String.format("#USR-%04d", user.getId()),
                    "Failed step-up re-authentication.", "FAILED", request);
            return ResponseEntity.status(401).body(Map.of("error", "Incorrect password."));
        }
        clearRateLimit(rateLimitKey);

        long authenticatedAt = java.time.Instant.now().getEpochSecond();
        activityLogService.log(
                user, "AUTH", "AUTH_STEP_UP_SUCCESS", String.format("#USR-%04d", user.getId()),
                "Step-up re-authentication succeeded.", "SUCCESS", request);

        Map<String, Object> response = new HashMap<>();
        response.put("accessToken", jwtTokenProvider.generateAccessToken(user, authenticatedAt));
        response.put("message", "Identity confirmed. Sensitive changes are now permitted.");
        return ResponseEntity.ok(response);
    }

    @PostMapping("/verify-session")
    public ResponseEntity<?> verifySession() {
        // Identity is derived exclusively from the authenticated JWT. The old
        // implementation trusted client-supplied userId/role, which let any
        // caller assert another account's identity.
        User principal = SecurityUtils.getCurrentUser();
        if (principal == null) {
            return ResponseEntity.status(401).body(Map.of("valid", false, "error", "Authentication required."));
        }

        return userRepository.findById(principal.getId())
                .<ResponseEntity<?>>map(user -> {
                    if ("Suspended".equalsIgnoreCase(user.getStatus())
                            || "Deactivated".equalsIgnoreCase(user.getStatus())) {
                        return ResponseEntity.status(403)
                                .body(Map.of("valid", false, "error", "Account access revoked."));
                    }
                    Map<String, Object> result = new HashMap<>();
                    result.put("valid", true);
                    result.put("role", user.getRole());
                    result.put("status", user.getStatus());
                    return ResponseEntity.ok(result);
                })
                .orElseGet(() -> ResponseEntity.status(401)
                        .body(Map.of("valid", false, "error", "User record no longer exists.")));
    }

    // ==========================================
    // 7. LOGOUT / SIGN OUT ALL DEVICES
    // ==========================================
    @PostMapping("/logout")
    @Transactional
    public ResponseEntity<?> logout(HttpServletRequest request) {
        User principal = SecurityUtils.getCurrentUser();
        if (principal == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Authentication required."));
        }

        User user = userRepository.findById(principal.getId()).orElse(null);
        if (user != null) {
            revokeAllRefreshTokens(user);
            // Bump the security stamp so the current access token (and every
            // other outstanding access token) stops working immediately.
            sessionRevocationService.revoke(user, "logout");
            activityLogService.log(
                    user, "AUTH", "AUTH_LOGOUT", String.format("#USR-%04d", user.getId()),
                    "User logged out; all refresh tokens revoked and session version bumped.", "SUCCESS", request);
        }

        return ResponseEntity.ok(Map.of("message", "Logged out successfully."));
    }

    @PostMapping("/logout-all")
    @Transactional
    public ResponseEntity<?> logoutAllDevices(HttpServletRequest request) {
        // Semantically identical to /logout: every refresh token is revoked and
        // the security stamp is bumped, which invalidates all devices at once.
        return logout(request);
    }
}

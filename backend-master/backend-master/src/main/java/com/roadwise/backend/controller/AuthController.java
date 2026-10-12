package com.roadwise.backend.controller;

import com.roadwise.backend.config.JwtTokenProvider;
import com.roadwise.backend.dto.*;
import com.roadwise.backend.model.*;
import com.roadwise.backend.repository.*;
import com.roadwise.backend.service.ActivityLogService;
import com.roadwise.backend.service.EmailService;
import com.roadwise.backend.service.NotificationService;
import com.roadwise.backend.util.PasswordPolicy;
import com.roadwise.backend.util.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private LoginRateLimitRepository loginRateLimitRepository;

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

    // Failed-attempt counters age out after this window instead of blocking forever.
    private static final Duration RATE_WINDOW = Duration.ofMinutes(15);
    private static final Duration RESET_REQUEST_COOLDOWN = Duration.ofSeconds(60);
    private static final int RESET_REQUEST_MAX_PER_WINDOW = 5;

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

    private boolean isWindowExpired(LoginRateLimit rateLimit) {
        return rateLimit.getFirstAttemptAt() != null
                && rateLimit.getFirstAttemptAt().isBefore(LocalDateTime.now().minus(RATE_WINDOW));
    }

    private boolean checkRateLimit(String keyIdentifier) {
        Optional<LoginRateLimit> rateLimitOpt = loginRateLimitRepository.findByKeyIdentifier(keyIdentifier);
        if (rateLimitOpt.isPresent()) {
            LoginRateLimit rateLimit = rateLimitOpt.get();
            if (isWindowExpired(rateLimit)) {
                loginRateLimitRepository.delete(rateLimit);
                return true;
            }
            if (rateLimit.getBlockedUntil() != null && LocalDateTime.now().isBefore(rateLimit.getBlockedUntil())) {
                return false;
            }
        }
        return true;
    }

    private void recordFailedAttempt(String keyIdentifier) {
        LoginRateLimit rateLimit = loginRateLimitRepository.findByKeyIdentifier(keyIdentifier).orElse(null);
        if (rateLimit == null || isWindowExpired(rateLimit)) {
            rateLimit = new LoginRateLimit(keyIdentifier);
            rateLimit.setAttemptCount(0);
            rateLimit.setFirstAttemptAt(LocalDateTime.now());
        }

        rateLimit.setAttemptCount(rateLimit.getAttemptCount() + 1);
        rateLimit.setLastAttemptAt(LocalDateTime.now());
        if (rateLimit.getAttemptCount() > 5) {
            long penaltySeconds = (long) Math.pow(2, rateLimit.getAttemptCount() - 5);
            penaltySeconds = Math.min(penaltySeconds, 900);
            rateLimit.setBlockedUntil(LocalDateTime.now().plusSeconds(penaltySeconds));
        }
        loginRateLimitRepository.save(rateLimit);
    }

    private boolean isResetRequestThrottled(String keyIdentifier) {
        Optional<LoginRateLimit> opt = loginRateLimitRepository.findByKeyIdentifier(keyIdentifier);
        if (opt.isEmpty()) {
            return false;
        }
        LoginRateLimit rateLimit = opt.get();
        if (isWindowExpired(rateLimit)) {
            return false;
        }
        if (rateLimit.getLastAttemptAt() != null
                && rateLimit.getLastAttemptAt().isAfter(LocalDateTime.now().minus(RESET_REQUEST_COOLDOWN))) {
            return true;
        }
        return rateLimit.getAttemptCount() >= RESET_REQUEST_MAX_PER_WINDOW;
    }

    private void recordResetRequest(String keyIdentifier) {
        LoginRateLimit rateLimit = loginRateLimitRepository.findByKeyIdentifier(keyIdentifier).orElse(null);
        if (rateLimit == null || isWindowExpired(rateLimit)) {
            rateLimit = new LoginRateLimit(keyIdentifier);
            rateLimit.setAttemptCount(0);
            rateLimit.setFirstAttemptAt(LocalDateTime.now());
        }
        rateLimit.setAttemptCount(rateLimit.getAttemptCount() + 1);
        rateLimit.setLastAttemptAt(LocalDateTime.now());
        loginRateLimitRepository.save(rateLimit);
    }

    private void clearRateLimit(String keyIdentifier) {
        loginRateLimitRepository.findByKeyIdentifier(keyIdentifier).ifPresent(loginRateLimitRepository::delete);
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

        if (user.getLockoutUntil() != null && LocalDateTime.now().isBefore(user.getLockoutUntil())) {
            activityLogService.log(
                    null, "AUTH", "AUTH_ACCOUNT_LOCKED", formattedUserId,
                    "Blocked login attempt during active lockout penalty.", "WARNING", request);
            long retryAfter = Math.max(
                    1, Duration.between(LocalDateTime.now(), user.getLockoutUntil()).getSeconds());
            return ResponseEntity.status(429)
                    .header("Retry-After", String.valueOf(retryAfter))
                    .body(Map.of("error", "Too many failed attempts. Account temporarily rate-limited for security."));
        }

        boolean passwordMatches;
        if (user.getPassword().startsWith("$2a$")
                || user.getPassword().startsWith("$2b$")
                || user.getPassword().startsWith("$2y$")) {
            passwordMatches = passwordEncoder.matches(password, user.getPassword());
        } else {
            passwordMatches = user.getPassword().equals(password);
            if (passwordMatches) {
                user.setPassword(passwordEncoder.encode(password));
            }
        }

        if (!passwordMatches) {
            int attempts = user.getFailedLoginAttempts() + 1;
            user.setFailedLoginAttempts(attempts);
            if (attempts > 5) {
                long penaltySeconds = Math.min((long) Math.pow(2, attempts - 5), 900);
                user.setLockoutUntil(LocalDateTime.now().plusSeconds(penaltySeconds));

                if (attempts == 6 || attempts == 15) {
                    notificationService.sendNotification(
                            getAdminId(),
                            "Security Alert: Brute-Force Activity",
                            "Lockout triggered for user: '" + username + "' after " + attempts + " consecutive failed attempts.",
                            "SECURITY"
                    );
                }
            }
            userRepository.save(user);
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

        String rawOtp = String.format("%06d", secureRandom.nextInt(1000000));
        String otpHash = passwordEncoder.encode(rawOtp);

        MfaChallenge challenge = new MfaChallenge(user, otpHash, LocalDateTime.now().plusMinutes(5));
        mfaChallengeRepository.save(challenge);

        String subject = "RoadWise - Login Verification Code";
        String body = "Hello " + user.getFirstName() + ",\n\n" +
                "Your Multi-Factor Authentication (MFA) code is: " + rawOtp + "\n\n" +
                "This code will expire in 5 minutes. Do not share this code with anyone.\n\n" +
                "If you did not attempt to log in, please contact the CPDO Administrator immediately.";

        try {
            emailService.sendEmail(user.getEmail(), subject, body);
        } catch (Exception e) {
            System.err.println(">>> [WARN] SMTP Delivery failed: " + e.getMessage());
        }

        activityLogService.log(
                user, "AUTH", "AUTH_MFA_REQUESTED", formattedUserId,
                "Credentials validated. 6-digit MFA OTP generated.", "SUCCESS", request);

        Map<String, Object> responseData = new HashMap<>();
        responseData.put("mfaRequired", true);
        responseData.put("userId", user.getId());
        responseData.put("message", "A 6-digit code has been sent to your email.");

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
        Long userId = verifyDto.getUserId();
        String submittedOtp = verifyDto.getOtp();

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found."));

        Optional<MfaChallenge> challengeOpt =
                mfaChallengeRepository.findTopByUserAndUsedFalseOrderByCreatedAtDesc(user);
        if (challengeOpt.isEmpty()) {
            return ResponseEntity.status(400).body(Map.of("error", "No active login session found. Please log in again."));
        }

        MfaChallenge challenge = challengeOpt.get();

        if (LocalDateTime.now().isAfter(challenge.getExpiresAt())) {
            challenge.setUsed(true);
            mfaChallengeRepository.save(challenge);
            return ResponseEntity.status(400).body(Map.of("error", "Verification code expired. Please log in again."));
        }

        if (challenge.getAttempts() >= challenge.getMaxAttempts()) {
            challenge.setUsed(true);
            mfaChallengeRepository.save(challenge);
            return ResponseEntity.status(429)
                    .body(Map.of("error", "Maximum OTP verification attempts exceeded. Please log in again."));
        }

        if (!passwordEncoder.matches(submittedOtp, challenge.getOtpCodeHash())) {
            challenge.setAttempts(challenge.getAttempts() + 1);
            mfaChallengeRepository.save(challenge);
            activityLogService.log(
                    user, "AUTH", "AUTH_MFA_FAILED", String.format("#USR-%04d", userId),
                    "Invalid MFA code submitted.", "FAILED", request);
            return ResponseEntity.status(401).body(Map.of("error", "Invalid verification code. Please try again."));
        }

        challenge.setUsed(true);
        mfaChallengeRepository.save(challenge);

        // MFA succeeded: only now is the login fully validated, so reset the
        // failed-password counters and per-IP/per-user rate limits.
        user.setFailedLoginAttempts(0);
        user.setLockoutUntil(null);
        userRepository.save(user);
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
        refreshTokenRepository.save(refreshTokenEntity);

        activityLogService.log(
                user, "AUTH", "AUTH_LOGIN_SUCCESS", String.format("#USR-%04d", user.getId()),
                "User successfully verified MFA and established active session for " + user.getRole(),
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
                user.bumpSessionVersion();
                userRepository.save(user);
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
        String newAccessToken = jwtTokenProvider.generateAccessToken(user);

        String newRawRefreshToken = UUID.randomUUID().toString() + "-" + UUID.randomUUID().toString();
        String newRefreshTokenHash = hashToken(newRawRefreshToken);
        LocalDateTime refreshExpiresAt = effectiveRefreshExpiry(now, sessionExpiresAt);

        token.setReplacedByTokenHash(newRefreshTokenHash);
        refreshTokenRepository.save(token);

        RefreshToken newTokenEntity = new RefreshToken(
                user, newRefreshTokenHash, refreshExpiresAt, request.getRemoteAddr(), sessionExpiresAt);
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
        user.setFailedLoginAttempts(0);
        user.setLockoutUntil(null);
        user.bumpSessionVersion();
        userRepository.save(user);

        resetToken.setUsed(true);
        passwordResetTokenRepository.save(resetToken);

        // Recovery invalidates every existing session.
        revokeAllRefreshTokens(user);
        clearRateLimit("RESET_EMAIL:" + email.toLowerCase(Locale.ROOT));

        activityLogService.log(
                user, "AUTH", "AUTH_PASSWORD_RESET_SUCCESS", String.format("#USR-%04d", user.getId()),
                "Password successfully updated via recovery verification.", "SUCCESS", request);

        return ResponseEntity.ok(Map.of("message", "Password successfully reset! You can now log in."));
    }

    // ==========================================
    // 6. VERIFY SESSION
    // ==========================================
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
            user.bumpSessionVersion();
            userRepository.save(user);
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

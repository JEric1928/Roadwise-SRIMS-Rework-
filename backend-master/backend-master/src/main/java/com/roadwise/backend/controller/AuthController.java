package com.roadwise.backend.controller;

import com.roadwise.backend.config.JwtTokenProvider;
import com.roadwise.backend.dto.*;
import com.roadwise.backend.model.*;
import com.roadwise.backend.repository.*;
import com.roadwise.backend.service.ActivityLogService;
import com.roadwise.backend.service.EmailService;
import com.roadwise.backend.service.NotificationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "${frontend.url}")
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

    private final SecureRandom secureRandom = new SecureRandom();

    private Long getAdminId() {
        return userRepository.findAll().stream()
                .filter(user -> user.getRole() != null && (user.getRole().equalsIgnoreCase("CPDO Admin") || user.getRole().equalsIgnoreCase("Admin")))
                .map(User::getId)
                .findFirst()
                .orElse(1L);
    }

    private String hashToken(String rawToken) {
        return DigestUtils.md5DigestAsHex(rawToken.getBytes(StandardCharsets.UTF_8));
    }

    private boolean checkRateLimit(String keyIdentifier) {
        Optional<LoginRateLimit> rateLimitOpt = loginRateLimitRepository.findByKeyIdentifier(keyIdentifier);
        if (rateLimitOpt.isPresent()) {
            LoginRateLimit rateLimit = rateLimitOpt.get();
            if (rateLimit.getBlockedUntil() != null && LocalDateTime.now().isBefore(rateLimit.getBlockedUntil())) {
                return false;
            }
        }
        return true;
    }

    private void recordFailedAttempt(String keyIdentifier) {
        LoginRateLimit rateLimit = loginRateLimitRepository.findByKeyIdentifier(keyIdentifier)
                .orElse(new LoginRateLimit(keyIdentifier));

        rateLimit.setAttemptCount(rateLimit.getAttemptCount() + 1);
        rateLimit.setLastAttemptAt(LocalDateTime.now());
        if (rateLimit.getAttemptCount() > 5) {
            long penaltySeconds = (long) Math.pow(2, rateLimit.getAttemptCount() - 5);
            penaltySeconds = Math.min(penaltySeconds, 900);
            rateLimit.setBlockedUntil(LocalDateTime.now().plusSeconds(penaltySeconds));
        }
        loginRateLimitRepository.save(rateLimit);
    }

    private void clearRateLimit(String keyIdentifier) {
        loginRateLimitRepository.findByKeyIdentifier(keyIdentifier).ifPresent(loginRateLimitRepository::delete);
    }

    // ==========================================
    // 1. LOGIN & MFA DISPATCH
    // ==========================================
    @PostMapping("/login")
    @Transactional
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequestDto loginDto, HttpServletRequest request) {
        String username = loginDto.getUsername();
        String password = loginDto.getPassword();
        String clientIp = request.getRemoteAddr();

        if (!checkRateLimit("IP:" + clientIp) || !checkRateLimit("USER:" + username)) {
            return ResponseEntity.status(429).body(Map.of("error", "Too many attempts. Account or IP temporarily rate-limited."));
        }

        Optional<User> userOpt = userRepository.findByUsername(username)
                .or(() -> userRepository.findByEmail(username));

        if (userOpt.isEmpty()) {
            recordFailedAttempt("IP:" + clientIp);
            recordFailedAttempt("USER:" + username);
            activityLogService.log(null, "AUTH", "AUTH_LOGIN_FAILED", username, "Failed login attempt for unknown account: '" + username + "'", "FAILED", request);
            return ResponseEntity.status(401).body(Map.of("error", "Invalid username or password."));
        }

        User user = userOpt.get();
        String formattedUserId = String.format("#USR-%04d", user.getId());

        if (user.getLockoutUntil() != null && LocalDateTime.now().isBefore(user.getLockoutUntil())) {
            activityLogService.log(null, "AUTH", "AUTH_ACCOUNT_LOCKED", formattedUserId, "Blocked login attempt during active lockout penalty.", "WARNING", request);
            return ResponseEntity.status(429).body(Map.of("error", "Too many failed attempts. Account temporarily rate-limited for security."));
        }

        boolean passwordMatches;
        if (user.getPassword().startsWith("$2a$") || user.getPassword().startsWith("$2b$") || user.getPassword().startsWith("$2y$")) {
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
            activityLogService.log(null, "AUTH", "AUTH_LOGIN_FAILED", formattedUserId, "Failed password verification for '" + username + "'", "FAILED", request);
            return ResponseEntity.status(401).body(Map.of("error", "Invalid username or password."));
        }

        user.setFailedLoginAttempts(0);
        user.setLockoutUntil(null);
        userRepository.save(user);
        clearRateLimit("IP:" + clientIp);
        clearRateLimit("USER:" + username);

        SystemSettings settings = systemSettingsRepository.findById(1L).orElse(null);
        if (settings != null && settings.isMaintenanceMode()) {
            if (!user.getRole().equalsIgnoreCase("Admin") && !user.getRole().equalsIgnoreCase("CPDO Admin")) {
                activityLogService.log(user, "AUTH", "AUTH_MAINTENANCE_BLOCKED", formattedUserId, "Login during maintenance mode.", "WARNING", request);
                return ResponseEntity.status(403).body(Map.of("error", "System is down for maintenance. Please try again later.", "type", "MAINTENANCE_MODE"));
            }
        }

        if ("Suspended".equalsIgnoreCase(user.getStatus())) {
            activityLogService.log(user, "AUTH", "AUTH_SUSPENDED_BLOCKED", formattedUserId, "Suspended account login attempt.", "FAILED", request);
            return ResponseEntity.status(403).body(Map.of("error", "Your account is currently Suspended. Please contact CPDO."));
        }

        if ("Deactivated".equalsIgnoreCase(user.getStatus())) {
            activityLogService.log(user, "AUTH", "AUTH_DEACTIVATED_BLOCKED", formattedUserId, "Deactivated account login attempt.", "FAILED", request);
            return ResponseEntity.status(403).body(Map.of("error", "Your account has been Deactivated. Access revoked."));
        }

        if (user.getEmail() == null || user.getEmail().isEmpty()) {
            return ResponseEntity.status(403).body(Map.of("error", "No official email is linked to this account. Cannot proceed with MFA. Contact Admin."));
        }

        String rawOtp = String.format("%06d", secureRandom.nextInt(1000000));
        String otpHash = passwordEncoder.encode(rawOtp);

        mfaChallengeRepository.findTopByUserAndUsedFalseOrderByCreatedAtDesc(user).ifPresent(c -> {
            c.setUsed(true);
            mfaChallengeRepository.save(c);
        });

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

        activityLogService.log(user, "AUTH", "AUTH_MFA_REQUESTED", formattedUserId, "Credentials validated. 6-digit MFA OTP generated.", "SUCCESS", request);

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
    public ResponseEntity<?> verifyMfa(@Valid @RequestBody MfaVerifyRequestDto verifyDto, HttpServletRequest request) {
        Long userId = verifyDto.getUserId();
        String submittedOtp = verifyDto.getOtp();

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found."));

        Optional<MfaChallenge> challengeOpt = mfaChallengeRepository.findTopByUserAndUsedFalseOrderByCreatedAtDesc(user);
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
            return ResponseEntity.status(429).body(Map.of("error", "Maximum OTP verification attempts exceeded. Please log in again."));
        }

        if (!passwordEncoder.matches(submittedOtp, challenge.getOtpCodeHash())) {
            challenge.setAttempts(challenge.getAttempts() + 1);
            mfaChallengeRepository.save(challenge);
            activityLogService.log(user, "AUTH", "AUTH_MFA_FAILED", String.format("#USR-%04d", userId), "Invalid MFA code submitted.", "FAILED", request);
            return ResponseEntity.status(401).body(Map.of("error", "Invalid verification code. Please try again."));
        }

        challenge.setUsed(true);
        mfaChallengeRepository.save(challenge);

        String accessToken = jwtTokenProvider.generateAccessToken(user);

        String rawRefreshToken = UUID.randomUUID().toString() + "-" + UUID.randomUUID().toString();
        String refreshTokenHash = hashToken(rawRefreshToken);
        LocalDateTime refreshExpiresAt = LocalDateTime.now().plusNanos(refreshTokenExpirationMs * 1_000_000L);

        RefreshToken refreshTokenEntity = new RefreshToken(user, refreshTokenHash, refreshExpiresAt, request.getRemoteAddr());
        refreshTokenRepository.save(refreshTokenEntity);

        activityLogService.log(user, "AUTH", "AUTH_LOGIN_SUCCESS", String.format("#USR-%04d", user.getId()), "User successfully verified MFA and established active session for " + user.getRole(), "SUCCESS", request);

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
    public ResponseEntity<?> refreshToken(@Valid @RequestBody RefreshTokenRequestDto refreshDto, HttpServletRequest request) {
        String rawToken = refreshDto.getRefreshToken();
        String tokenHash = hashToken(rawToken);

        Optional<RefreshToken> refreshTokenOpt = refreshTokenRepository.findByTokenHash(tokenHash);
        if (refreshTokenOpt.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid refresh token."));
        }

        RefreshToken token = refreshTokenOpt.get();
        if (token.isRevoked() || LocalDateTime.now().isAfter(token.getExpiresAt())) {
            return ResponseEntity.status(401).body(Map.of("error", "Expired or revoked refresh token."));
        }

        token.setRevoked(true);

        User user = token.getUser();
        String newAccessToken = jwtTokenProvider.generateAccessToken(user);

        String newRawRefreshToken = UUID.randomUUID().toString() + "-" + UUID.randomUUID().toString();
        String newRefreshTokenHash = hashToken(newRawRefreshToken);
        LocalDateTime refreshExpiresAt = LocalDateTime.now().plusNanos(refreshTokenExpirationMs * 1_000_000L);

        token.setReplacedByTokenHash(newRefreshTokenHash);
        refreshTokenRepository.save(token);

        RefreshToken newTokenEntity = new RefreshToken(user, newRefreshTokenHash, refreshExpiresAt, request.getRemoteAddr());
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
    public ResponseEntity<?> requestPasswordReset(@Valid @RequestBody PasswordResetRequestDto resetDto, HttpServletRequest request) {
        String email = resetDto.getEmail();

        Optional<User> userOpt = userRepository.findByEmail(email);
        if (userOpt.isEmpty()) {
            return ResponseEntity.ok(Map.of("message", "If this email exists, a recovery code will be sent shortly."));
        }

        User user = userOpt.get();
        String formattedUserId = String.format("#USR-%04d", user.getId());

        String rawOtp = String.format("%06d", secureRandom.nextInt(1000000));
        String otpHash = passwordEncoder.encode(rawOtp);

        passwordResetTokenRepository.findTopByUserAndUsedFalseOrderByCreatedAtDesc(user).ifPresent(p -> {
            p.setUsed(true);
            passwordResetTokenRepository.save(p);
        });

        PasswordResetToken resetToken = new PasswordResetToken(user, otpHash, LocalDateTime.now().plusMinutes(10));
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

        activityLogService.log(user, "AUTH", "AUTH_PASSWORD_RESET_REQUEST", formattedUserId, "Password recovery OTP requested.", "SUCCESS", request);

        return ResponseEntity.ok(Map.of("message", "If this email exists, a recovery code will be sent shortly.", "userId", user.getId()));
    }

    // ==========================================
    // 5. FORGOT PASSWORD CONFIRM / RESET
    // ==========================================
    @PostMapping("/forgot-password/reset")
    @Transactional
    public ResponseEntity<?> resetPassword(@Valid @RequestBody PasswordResetConfirmDto confirmDto, HttpServletRequest request) {
        Long userId = confirmDto.getUserId();
        String submittedOtp = confirmDto.getOtp();
        String newPassword = confirmDto.getNewPassword();

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found."));

        Optional<PasswordResetToken> tokenOpt = passwordResetTokenRepository.findTopByUserAndUsedFalseOrderByCreatedAtDesc(user);
        if (tokenOpt.isEmpty()) {
            return ResponseEntity.status(400).body(Map.of("error", "No active password reset request. Please request a new code."));
        }

        PasswordResetToken resetToken = tokenOpt.get();

        if (LocalDateTime.now().isAfter(resetToken.getExpiresAt())) {
            resetToken.setUsed(true);
            passwordResetTokenRepository.save(resetToken);
            return ResponseEntity.status(400).body(Map.of("error", "Reset code has expired. Please request a new one."));
        }

        if (!passwordEncoder.matches(submittedOtp, resetToken.getTokenHash())) {
            activityLogService.log(user, "AUTH", "AUTH_PASSWORD_RESET_FAILED", String.format("#USR-%04d", userId), "Invalid reset OTP submitted.", "FAILED", request);
            return ResponseEntity.status(401).body(Map.of("error", "Invalid recovery code. Please try again."));
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        user.setFailedLoginAttempts(0);
        user.setLockoutUntil(null);
        userRepository.save(user);

        resetToken.setUsed(true);
        passwordResetTokenRepository.save(resetToken);

        List<RefreshToken> activeRefreshTokens = refreshTokenRepository.findByUserAndRevokedFalse(user);
        for (RefreshToken rt : activeRefreshTokens) {
            rt.setRevoked(true);
            refreshTokenRepository.save(rt);
        }

        activityLogService.log(user, "AUTH", "AUTH_PASSWORD_RESET_SUCCESS", String.format("#USR-%04d", user.getId()), "Password successfully updated via recovery verification.", "SUCCESS", request);

        return ResponseEntity.ok(Map.of("message", "Password successfully reset! You can now log in."));
    }

    // ==========================================
    // 6. VERIFY SESSION
    // ==========================================
    @PostMapping("/verify-session")
    public ResponseEntity<?> verifySession(@RequestBody Map<String, Object> payload, HttpServletRequest request) {
        Object rawId = payload.get("userId");
        String claimedRole = (String) payload.get("role");

        if (rawId == null || claimedRole == null) {
            return ResponseEntity.status(401).body(Map.of("valid", false, "error", "Missing session identity."));
        }

        Long userId;
        try {
            userId = Long.valueOf(rawId.toString());
        } catch (NumberFormatException e) {
            return ResponseEntity.status(401).body(Map.of("valid", false, "error", "Invalid user ID format."));
        }

        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("valid", false, "error", "User record no longer exists."));
        }

        User user = userOpt.get();

        if ("Suspended".equalsIgnoreCase(user.getStatus()) || "Deactivated".equalsIgnoreCase(user.getStatus())) {
            return ResponseEntity.status(403).body(Map.of("valid", false, "error", "Account access revoked."));
        }

        if (!user.getRole().equalsIgnoreCase(claimedRole)) {
            activityLogService.log(
                    user,
                    "SECURITY",
                    "ROLE_SPOOF_ATTEMPT",
                    String.format("#USR-%04d", user.getId()),
                    "Client role mismatch detected. Claimed: '" + claimedRole + "', DB: '" + user.getRole() + "'.",
                    "WARNING",
                    request
            );
            return ResponseEntity.status(403).body(Map.of("valid", false, "error", "Role mismatch detected."));
        }

        Map<String, Object> result = new HashMap<>();
        result.put("valid", true);
        result.put("role", user.getRole());
        result.put("status", user.getStatus());

        return ResponseEntity.ok(result);
    }
}

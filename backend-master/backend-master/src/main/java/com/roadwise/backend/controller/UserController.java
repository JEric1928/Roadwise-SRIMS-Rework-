package com.roadwise.backend.controller;

import com.roadwise.backend.dto.UserResponseDto;
import com.roadwise.backend.model.MfaChallenge;
import com.roadwise.backend.model.PasswordResetToken;
import com.roadwise.backend.model.RefreshToken;
import com.roadwise.backend.model.User;
import com.roadwise.backend.repository.BarangayRepository;
import com.roadwise.backend.repository.MfaChallengeRepository;
import com.roadwise.backend.repository.PasswordResetTokenRepository;
import com.roadwise.backend.repository.RefreshTokenRepository;
import com.roadwise.backend.repository.UserRepository;
import com.roadwise.backend.service.ActivityLogService;
import com.roadwise.backend.service.EmailService;
import com.roadwise.backend.service.NotificationService;
import com.roadwise.backend.service.RateLimitService;
import com.roadwise.backend.service.SessionRevocationService;
import com.roadwise.backend.service.SupabaseStorageService;
import com.roadwise.backend.util.PasswordPolicy;
import com.roadwise.backend.util.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/users")
public class UserController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private EmailService emailService;

    @Autowired
    private BarangayRepository barangayRepository;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private ActivityLogService activityLogService;

    // 🚀 INJECTED SUPABASE CLOUD STORAGE SERVICE
    @Autowired
    private SupabaseStorageService supabaseStorageService;

    // 🌐 DYNAMIC FRONTEND DOMAIN INJECTION
    @Value("${frontend.url:https://roadwise-csjdm.com}")
    private String frontendUrl;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private MfaChallengeRepository mfaChallengeRepository;

    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Autowired
    private RateLimitService rateLimitService;

    @Autowired
    private SessionRevocationService sessionRevocationService;

    /**
     * True when the caller is the target user or a system administrator.
     * Used to stop one user from mutating another user's profile/credentials.
     */
    private boolean isSelfOrAdmin(Long targetUserId) {
        User current = SecurityUtils.getCurrentUser();
        if (current == null || current.getId() == null) {
            return false;
        }
        if (current.getId().equals(targetUserId)) {
            return true;
        }
        String role = current.getRole();
        return role != null && (role.equalsIgnoreCase("ADMIN") || role.equalsIgnoreCase("CPDO Admin"));
    }

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private String generateTemporaryPassword() {
        final String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789!@#$";
        StringBuilder sb = new StringBuilder(14);
        for (int i = 0; i < 14; i++) {
            sb.append(chars.charAt(SECURE_RANDOM.nextInt(chars.length())));
        }
        return sb.toString();
    }

    /**
     * Credential-change remediation: revoke every refresh token, bump the
     * access-token security stamp, and consume outstanding MFA/recovery
     * challenges so a pre-change OTP cannot survive the change.
     */
    private void revokeUserSessionsAndChallenges(User user, String reason) {
        List<RefreshToken> activeTokens = refreshTokenRepository.findByUserAndRevokedFalse(user);
        for (RefreshToken token : activeTokens) {
            token.setRevoked(true);
        }
        refreshTokenRepository.saveAll(activeTokens);

        List<MfaChallenge> pendingChallenges = mfaChallengeRepository.findByUserAndUsedFalse(user);
        for (MfaChallenge challenge : pendingChallenges) {
            challenge.setUsed(true);
        }
        mfaChallengeRepository.saveAll(pendingChallenges);

        List<PasswordResetToken> pendingResets = passwordResetTokenRepository.findByUserAndUsedFalse(user);
        for (PasswordResetToken resetToken : pendingResets) {
            resetToken.setUsed(true);
        }
        passwordResetTokenRepository.saveAll(pendingResets);

        sessionRevocationService.revoke(user, reason);
    }

    // ==========================================
    // 1. UPDATE TEXT PROFILE DETAILS
    // ==========================================
    @PutMapping("/{id}/profile")
    public ResponseEntity<?> updateProfile(
            @PathVariable Long id,
            @RequestBody Map<String, String> updates,
            HttpServletRequest request) {

        if (!isSelfOrAdmin(id)) {
            return ResponseEntity.status(403).body(Map.of("error", "You may only update your own profile."));
        }

        Optional<User> userOpt = userRepository.findById(id);
        if (userOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        User user = userOpt.get();

        if (updates.containsKey("phoneNumber")) user.setPhoneNumber(updates.get("phoneNumber"));
        // Email is intentionally NOT changed here: it is the MFA/recovery
        // destination, so it only changes through the verified change-email flow
        // (current password + OTP confirmed at the new address).
        if (updates.containsKey("gender")) user.setGender(updates.get("gender"));
        if (updates.containsKey("birthday") && updates.get("birthday") != null && !updates.get("birthday").isEmpty()) {
            user.setBirthday(LocalDate.parse(updates.get("birthday")));
        }

        userRepository.save(user);

        activityLogService.log(
                user,
                "USER",
                "USER_PROFILE_UPDATED",
                "#USR-" + user.getId(),
                "Updated personal profile information (contact/demographics).",
                "SUCCESS",
                request
        );

        return ResponseEntity.ok(Map.of(
                "message", "Profile updated successfully",
                "email", user.getEmail() == null ? "" : user.getEmail()));
    }

    // ==========================================
    // 2. UPLOAD PROFILE PICTURE (SUPABASE STORAGE)
    // ==========================================
    @PostMapping(value = "/{id}/profile-picture", consumes = {"multipart/form-data"})
    public ResponseEntity<?> uploadProfilePicture(
            @PathVariable Long id,
            @RequestParam("profilePicture") MultipartFile file,
            HttpServletRequest request) {
        if (!isSelfOrAdmin(id)) {
            return ResponseEntity.status(403).body(Map.of("error", "You may only update your own profile picture."));
        }
        try {
            if (userRepository.findById(id).isEmpty()) return ResponseEntity.notFound().build();

            // Upload FIRST, then reload the row: avatar upload can be slow, and
            // saving a user loaded before it would overwrite concurrent security
            // changes (@Version turns any residual race into a 409).
            String cloudAvatarUrl = supabaseStorageService.uploadImage(file);

            User user = userRepository.findById(id).orElseThrow();
            user.setProfilePicture(cloudAvatarUrl);
            userRepository.save(user);

            // ⏱️ AUDIT LOG: AVATAR CHANGE
            activityLogService.log(
                    user,
                    "USER",
                    "USER_AVATAR_UPDATED",
                    "#USR-" + user.getId(),
                    "Updated account profile picture.",
                    "SUCCESS",
                    request
            );

            return ResponseEntity.ok(Map.of(
                    "message", "Profile picture updated successfully!",
                    "profilePicture", cloudAvatarUrl
            ));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to upload profile picture: " + e.getMessage()));
        }
    }

    // ==========================================
    // 3. SECURE PASSWORD UPDATE (RATE-LIMITED)
    //    Failed current-password attempts are throttled per user through
    //    RateLimitService on a PASSWORD_CHANGE:<id> key. Account login state is
    //    deliberately left untouched so an administrator or malicious actor
    //    targeting another account ID cannot lock that account out of login.
    // ==========================================

    /**
     * Verifies a plaintext candidate password against the stored hash, transparently
     * migrating legacy plaintext rows to BCrypt on successful match (same strategy
     * as AuthController login).
     */
    private boolean verifyAndMigratePassword(User user, String rawPassword) {
        String stored = user.getPassword();
        if (stored == null || rawPassword == null) {
            return false;
        }
        if (stored.startsWith("$2a$") || stored.startsWith("$2b$") || stored.startsWith("$2y$")) {
            return passwordEncoder.matches(rawPassword, stored);
        }
        // Legacy plaintext row: compare and upgrade to BCrypt on success.
        if (stored.equals(rawPassword)) {
            user.setPassword(passwordEncoder.encode(rawPassword));
            return true;
        }
        return false;
    }

    @PutMapping("/{id}/password")
    @Transactional
    public ResponseEntity<?> updatePassword(
            @PathVariable Long id,
            @RequestBody Map<String, String> payload,
            HttpServletRequest request) {

        if (!isSelfOrAdmin(id)) {
            return ResponseEntity.status(403).body(Map.of("error", "You may only change your own password."));
        }

        Optional<User> userOpt = userRepository.findById(id);
        if (userOpt.isEmpty()) return ResponseEntity.notFound().build();

        User user = userOpt.get();
        String currentPassword = payload.get("currentPassword");
        String newPassword = payload.get("newPassword");

        String passwordFailureKey = "PASSWORD_CHANGE:" + id;
        if (rateLimitService.isBlocked(passwordFailureKey)) {
            return ResponseEntity.status(429)
                    .header("Retry-After", "60")
                    .body(Map.of("error", "Too many failed attempts. Please try again in a minute."));
        }

        if (!verifyAndMigratePassword(user, currentPassword)) {
            rateLimitService.recordFailure(passwordFailureKey);
            return ResponseEntity.status(401).body(Map.of("error", "Incorrect current password."));
        }
        rateLimitService.clear(passwordFailureKey);

        String policyError = PasswordPolicy.validate(newPassword);
        if (policyError != null) {
            return ResponseEntity.badRequest().body(Map.of("error", policyError));
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        user.setPasswordChangeRequired(false);
        user.setTempPasswordExpiresAt(null);
        // Revoke every existing session, outstanding refresh token and pending
        // MFA/recovery challenge; the change is not complete until they are gone.
        revokeUserSessionsAndChallenges(user, "password changed");
        userRepository.save(user);

        activityLogService.log(
                user,
                "AUTH",
                "USER_PASSWORD_UPDATED",
                "#USR-" + user.getId(),
                "User successfully changed account password.",
                "SUCCESS",
                request
        );

        return ResponseEntity.ok(Map.of("message", "Password updated successfully!"));
    }

    // ==========================================
    // 0. GET ALL SYSTEM USERS
    // ==========================================
    @GetMapping
    @PreAuthorize("@authz.has('user:read')")
    public ResponseEntity<List<UserResponseDto>> getAllUsers() {
        List<UserResponseDto> users = userRepository.findAll().stream()
                .map(UserResponseDto::from)
                .toList();
        return ResponseEntity.ok(users);
    }

    // ==========================================
    // 4. FETCH ALL BARANGAY OFFICIALS
    // ==========================================
    @GetMapping("/officials")
    @PreAuthorize("hasAnyRole('ENGINEER', 'City Engineer', 'ADMIN', 'CPDO Admin')")
    public ResponseEntity<List<UserResponseDto>> getBarangayOfficials() {
        List<UserResponseDto> officials = userRepository.findByRole("BARANGAY").stream()
                .map(UserResponseDto::from)
                .toList();
        return ResponseEntity.ok(officials);
    }

    // ==========================================
    // 5. PROVISION NEW BARANGAY OFFICIAL ACCOUNT
    // ==========================================
    @PostMapping("/register")
    @PreAuthorize("@authz.has('user:provision')")
    public ResponseEntity<?> registerOfficial(
            @RequestBody Map<String, String> payload,
            HttpServletRequest request) {

        String username = payload.get("username");
        String rawPassword = payload.get("password");
        String email = payload.get("email") != null ? payload.get("email").trim() : "";

        if (username == null || username.trim().isEmpty()) {
            return ResponseEntity.status(400).body(Map.of("error", "Username is required."));
        }
        if (rawPassword == null || rawPassword.trim().isEmpty()) {
            return ResponseEntity.status(400).body(Map.of("error", "Password is required."));
        }
        String policyError = PasswordPolicy.validate(rawPassword);
        if (policyError != null) {
            return ResponseEntity.status(400).body(Map.of("error", policyError));
        }
        if (userRepository.findByUsername(username).isPresent()) {
            return ResponseEntity.status(400).body(Map.of("error", "Username already exists!"));
        }
        if (!email.isEmpty() && userRepository.findByEmail(email).isPresent()) {
            return ResponseEntity.status(400).body(Map.of("error", "Email already exists!"));
        }

        // This endpoint provisions Barangay Officials only. Administrators must
        // never be able to inject a privileged role through the request body.
        String requestedRole = payload.get("role");
        if (requestedRole != null && !requestedRole.trim().isEmpty()
                && !requestedRole.equalsIgnoreCase("BARANGAY")) {
            return ResponseEntity.status(403).body(Map.of("error", "Only Barangay Official accounts can be provisioned here."));
        }

        User newUser = new User();
        newUser.setFirstName(payload.get("firstName"));
        newUser.setMiddleName(payload.get("middleName"));
        newUser.setLastName(payload.get("lastName"));
        newUser.setEmail(payload.get("email"));
        newUser.setUsername(username);
        // Store credentials hashed (BCrypt), consistent with AuthController.
        newUser.setPassword(passwordEncoder.encode(rawPassword));
        newUser.setPasswordChangeRequired(true);
        newUser.setTempPasswordExpiresAt(LocalDateTime.now().plusHours(24));
        newUser.setRole("BARANGAY");
        newUser.setStatus("Active");

        if (payload.get("barangayId") != null && !payload.get("barangayId").isEmpty()) {
            Long brgyId = Long.parseLong(payload.get("barangayId"));
            List<User> existingOfficials = userRepository.findByBarangayId(brgyId);

            Optional<User> activeOfficial = existingOfficials.stream()
                    .filter(u -> u.getStatus() == null || !u.getStatus().equalsIgnoreCase("Deactivated"))
                    .findFirst();

            if (activeOfficial.isPresent()) {
                User occupiedBy = activeOfficial.get();
                return ResponseEntity.status(400).body(Map.of("error",
                        "This Barangay already has an assigned official (" + occupiedBy.getFirstName() + " " + occupiedBy.getLastName() + "). Only 1 official is allowed per Barangay."));
            }

            barangayRepository.findById(brgyId).ifPresent(newUser::setBarangay);
        }

        User savedUser = userRepository.save(newUser);

        notificationService.sendNotification(
                savedUser.getId(),
                "Account Provisioned",
                "Welcome to RoadWise! Your official account has been created by the City Admin. Please change your password for security.",
                "ACCOUNT"
        );

        if (savedUser.getEmail() != null && !savedUser.getEmail().isEmpty()) {
            String subject = "Welcome to RoadWise - Your Account Credentials";
            String emailBody = "Hello " + savedUser.getFirstName() + ",\n\n" +
                    "Your official RoadWise Barangay Official account has been provisioned.\n\n" +
                    "Username: " + savedUser.getUsername() + "\n" +
                    "Temporary Password: " + rawPassword + "\n\n" +
                    "Please log in here: " + frontendUrl + "/login.html\n\n" +
                    "For security purposes, please change your password immediately after logging in.\n\n" +
                    "Best regards,\nCPDO Administrator - RoadWise SJDM";

            emailService.sendEmail(savedUser.getEmail(), subject, emailBody);
        }

        // AUDIT: the actor is the authenticated principal. A client-supplied
        // adminId used to be honoured here, which let an authorised admin have a
        // provisioning action attributed to a different account. Same rule as
        // manageUserRecord and emergencyPasswordReset.
        User adminActor = SecurityUtils.getCurrentUser();

        String brgyName = savedUser.getBarangay() != null ? savedUser.getBarangay().getBarangayName() : "City Central";
        activityLogService.log(
                adminActor,
                "USER",
                "USER_PROVISIONED",
                "#USR-" + savedUser.getId(),
                "Provisioned new " + savedUser.getRole() + " account for " + savedUser.getFirstName() + " " + savedUser.getLastName() + " (Assigned: " + brgyName + ").",
                "SUCCESS",
                request
        );

        return ResponseEntity.ok(Map.of("message", "Official successfully provisioned!"));
    }

    // ==========================================
    // 6. GET SINGLE USER DATA
    // ==========================================
    @GetMapping("/{id}")
    public ResponseEntity<?> getUserById(@PathVariable Long id) {
        if (!isSelfOrAdmin(id)) {
            return ResponseEntity.status(403).body(Map.of("error", "You may only view your own account."));
        }
        return userRepository.findById(id)
                .<ResponseEntity<?>>map(user -> ResponseEntity.ok(UserResponseDto.from(user)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ==========================================
    // 7. ADMIN: UPDATE OFFICIAL RECORD & STATUS
    // ==========================================
    @PutMapping("/{id}/manage")
    @PreAuthorize("@authz.canActOn(#id, 'user:manage', #approvalId)")
    @Transactional
    public ResponseEntity<?> manageUserRecord(
            @PathVariable Long id,
            @RequestHeader(value = "X-Approval-Id", required = false) Long approvalId,
            @RequestBody Map<String, String> updates,
            HttpServletRequest request) {

        Optional<User> userOpt = userRepository.findById(id);
        if (userOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        User user = userOpt.get();
        if (updates.containsKey("firstName")) user.setFirstName(updates.get("firstName"));
        if (updates.containsKey("middleName")) user.setMiddleName(updates.get("middleName"));
        if (updates.containsKey("lastName")) user.setLastName(updates.get("lastName"));

        if (updates.containsKey("email")) {
            String newEmail = updates.get("email") != null ? updates.get("email").trim() : "";
            if (newEmail.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Email cannot be empty."));
            }
            if (!newEmail.contains("@") || !newEmail.contains(".")) {
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid email format."));
            }
            Optional<User> emailOwner = userRepository.findByEmail(newEmail);
            if (emailOwner.isPresent() && !emailOwner.get().getId().equals(id)) {
                return ResponseEntity.badRequest().body(Map.of("error", "Email is already registered to another user."));
            }
            user.setEmail(newEmail);
        }

        String oldStatus = user.getStatus();
        String newStatus = updates.get("status");

        if (newStatus != null && !newStatus.isEmpty()) {
            boolean statusChanged = oldStatus == null || !oldStatus.equalsIgnoreCase(newStatus);
            user.setStatus(newStatus);

            if (statusChanged && ("Suspended".equalsIgnoreCase(newStatus)
                    || "Deactivated".equalsIgnoreCase(newStatus))) {
                revokeUserSessionsAndChallenges(user, "account status changed to " + newStatus);
            }

            if (oldStatus != null && statusChanged) {
                notificationService.sendNotification(
                        user.getId(),
                        "Account Status Update",
                        "Your account status has been updated to: " + newStatus + ".",
                        "ACCOUNT"
                );
            }
        }

        if (updates.containsKey("barangayId") && updates.get("barangayId") != null && !updates.get("barangayId").isEmpty()) {
            try {
                Long brgyId = Long.parseLong(updates.get("barangayId"));
                List<User> existingOfficials = userRepository.findByBarangayId(brgyId);

                Optional<User> conflictOfficial = existingOfficials.stream()
                        .filter(u -> !u.getId().equals(id) && (u.getStatus() == null || !u.getStatus().equalsIgnoreCase("Deactivated")))
                        .findFirst();

                if (conflictOfficial.isPresent()) {
                    User occupiedBy = conflictOfficial.get();
                    return ResponseEntity.status(400).body(Map.of("error",
                            "Cannot reassign: This Barangay is already assigned to " + occupiedBy.getFirstName() + " " + occupiedBy.getLastName() + "."));
                }

                barangayRepository.findById(brgyId).ifPresent(user::setBarangay);
            } catch (NumberFormatException e) {
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid barangay ID format."));
            }
        }

        userRepository.save(user);

        // AUDIT: actor resolved from the authenticated principal. The
        // client-supplied adminId in the request body is ignored so audit
        // attribution cannot be forged — this path used to accept it, which is
        // what emergencyPasswordReset already guards against.
        User adminActor = SecurityUtils.getCurrentUser();

        String statusNotice = (newStatus != null && !newStatus.equalsIgnoreCase(oldStatus)) ? " Status changed to '" + newStatus + "'." : "";
        activityLogService.log(
                adminActor,
                "USER",
                "USER_RECORD_MANAGED",
                "#USR-" + user.getId(),
                "Updated official profile/jurisdiction for " + user.getFirstName() + " " + user.getLastName() + "." + statusNotice,
                "SUCCESS",
                request
        );

        return ResponseEntity.ok(Map.of("message", "Official record updated successfully!"));
    }

    // ==========================================
    // 8. ADMIN: EMERGENCY PASSWORD RESET
    // ==========================================
    @PutMapping("/{id}/emergency-reset")
    @PreAuthorize("@authz.canActOn(#id, 'user:reset-password', #approvalId)")
    @Transactional
    public ResponseEntity<?> emergencyPasswordReset(
            @PathVariable Long id,
            @RequestHeader(value = "X-Approval-Id", required = false) Long approvalId,
            @RequestBody(required = false) Map<String, String> payload,
            HttpServletRequest request) {

        Optional<User> userOpt = userRepository.findById(id);
        if (userOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        User user = userOpt.get();
        // Generate a per-reset random temporary password instead of a fixed,
        // publicly known value. It is hashed at rest and returned once to the
        // authenticated administrator so it can be communicated securely.
        String tempPassword = generateTemporaryPassword();
        user.setPassword(passwordEncoder.encode(tempPassword));
        user.setPasswordChangeRequired(true);
        user.setTempPasswordExpiresAt(LocalDateTime.now().plusHours(24));
        // Force re-authentication: revoke refresh tokens, access stamps and any
        // pending MFA/recovery challenge for the account.
        revokeUserSessionsAndChallenges(user, "administrator password reset");
        userRepository.save(user);

        notificationService.sendNotification(
                user.getId(),
                "Security Alert",
                "Your password has been reset by the City Administrator. Please update it immediately.",
                "SECURITY"
        );

        if (user.getEmail() != null && !user.getEmail().isEmpty()) {
            try {
                emailService.sendEmail(
                        user.getEmail(),
                        "RoadWise - Password Reset",
                        "Hello " + user.getFirstName() + ",\n\n" +
                        "Your RoadWise password was reset by the City Administrator.\n\n" +
                        "Temporary Password: " + tempPassword + "\n\n" +
                        "Please log in and change it immediately.\n\n" +
                        "Best regards,\nCPDO Administrator - RoadWise SJDM");
            } catch (Exception e) {
                System.err.println("Emergency reset email delivery failed: " + e.getMessage());
            }
        }

        // ⏱️ AUDIT: actor resolved from the authenticated principal; the client-supplied
        // adminId in the payload is ignored so audit attribution cannot be forged.
        User adminActor = SecurityUtils.getCurrentUser();
        // No fallback to an arbitrary administrator: if the principal is somehow
        // absent the entry is recorded as System Automation rather than
        // attributed to whichever admin the query happened to return first.

        activityLogService.log(
                adminActor,
                "AUTH",
                "ADMIN_EMERGENCY_PASSWORD_RESET",
                "#USR-" + user.getId(),
                "Admin performed emergency password reset for " + user.getFirstName() + " " + user.getLastName() + ".",
                "WARNING",
                request
        );

        return ResponseEntity.ok(Map.of(
                "message", "Password successfully reset.",
                "tempPassword", tempPassword
        ));
    }

    // ==========================================
    // 9. CPDO ADMIN: FORMAL OFFICE TURNOVER / SUCCESSION
    // ==========================================
    @PostMapping("/handover-admin")
    @PreAuthorize("@authz.has('user:grant-role')")
    @Transactional
    public ResponseEntity<?> handoverAdminOffice(
            @RequestBody Map<String, String> payload,
            HttpServletRequest request) {

        User caller = SecurityUtils.getCurrentUser();
        String currentAdminIdStr = payload.get("currentAdminId");
        String currentPassword = payload.get("currentPassword");
        String newFirstName = payload.get("firstName");
        String newMiddleName = payload.get("middleName");
        String newLastName = payload.get("lastName");
        String newEmail = payload.get("email");
        String newUsername = payload.get("username");
        String newPhone = payload.get("phoneNumber");
        String memoNumber = payload.get("memoNumber");

        if (currentAdminIdStr == null || currentPassword == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Current administrator credentials are required."));
        }

        Long currentAdminId = Long.valueOf(currentAdminIdStr);
        if (caller == null || !caller.getId().equals(currentAdminId)) {
            return ResponseEntity.status(403).body(Map.of("error", "You may only turn over your own administrative account."));
        }
        Optional<User> currentAdminOpt = userRepository.findById(currentAdminId);
        if (currentAdminOpt.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "Current administrator account not found."));
        }

        User currentAdmin = currentAdminOpt.get();

        String adminHandoverThrottle = "HANDOVER_ADMIN:" + currentAdmin.getId();
        if (rateLimitService.isBlocked(adminHandoverThrottle)) {
            return ResponseEntity.status(429)
                    .header("Retry-After", "60")
                    .body(Map.of("error", "Too many failed attempts. Please try again in a minute."));
        }

        // 🛡️ 1. SECURITY CHECK: Verify Outgoing Admin Password (BCrypt-aware)
        if (!verifyAndMigratePassword(currentAdmin, currentPassword)) {
            rateLimitService.recordFailure(adminHandoverThrottle);
            userRepository.save(currentAdmin);
            activityLogService.log(
                    currentAdmin,
                    "SECURITY",
                    "ADMIN_HANDOVER_UNAUTHORIZED",
                    "#USR-" + currentAdmin.getId(),
                    "Unauthorized turnover attempt: Incorrect current password submitted.",
                    "FAILED",
                    request
            );
            return ResponseEntity.status(401).body(Map.of("error", "Incorrect current password. Turnover authorization rejected."));
        }
        rateLimitService.clear(adminHandoverThrottle);

        // 🛡️ 2. UNIQUE CONFLICT CHECKS
        if (userRepository.findByUsername(newUsername).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Username '" + newUsername + "' is already taken."));
        }

        if (userRepository.findByEmail(newEmail).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Official email '" + newEmail + "' is already registered to another user."));
        }

        // 🛡️ 3. GENERATE SECURE TEMPORARY CREDENTIALS
        String tempPassword = generateTemporaryPassword();

        // 4. PROVISION INCOMING SUCCESSOR
        User newAdmin = new User();
        newAdmin.setFirstName(newFirstName != null ? newFirstName.trim() : "");
        newAdmin.setMiddleName(newMiddleName != null ? newMiddleName.trim() : "");
        newAdmin.setLastName(newLastName != null ? newLastName.trim() : "");
        newAdmin.setEmail(newEmail != null ? newEmail.trim() : "");
        newAdmin.setUsername(newUsername != null ? newUsername.trim() : "");
        newAdmin.setPhoneNumber(newPhone != null ? newPhone.trim() : "");
        newAdmin.setPassword(passwordEncoder.encode(tempPassword));
        newAdmin.setPasswordChangeRequired(true);
        newAdmin.setTempPasswordExpiresAt(LocalDateTime.now().plusHours(24));
        newAdmin.setRole("CPDO Admin");
        newAdmin.setStatus("Active");
        newAdmin.setProfilePicture("no_image.jpg");

        User savedNewAdmin = userRepository.save(newAdmin);

        // 5. DEACTIVATE OUTGOING PREDECESSOR (Preserve audit integrity)
        currentAdmin.setStatus("Deactivated");
        revokeUserSessionsAndChallenges(currentAdmin, "administrator office handover");
        userRepository.save(currentAdmin);

        // 📋 6. WRITE PERMANENT SYSTEM ACTIVITY LOG
        String memoText = (memoNumber != null && !memoNumber.trim().isEmpty()) ? " [Memo Ref: " + memoNumber.trim() + "]" : "";
        activityLogService.log(
                currentAdmin,
                "AUTH",
                "OFFICIAL_OFFICE_TURNOVER",
                "#USR-" + savedNewAdmin.getId(),
                "CPDO Admin " + currentAdmin.getFirstName() + " " + currentAdmin.getLastName() +
                        " officially turned over administrative office to " + savedNewAdmin.getFirstName() + " " + savedNewAdmin.getLastName() + memoText + ".",
                "SUCCESS",
                request
        );

        // 📧 7. SEND SUCCESSION DISPATCH EMAIL TO NEW ADMIN
        if (savedNewAdmin.getEmail() != null && !savedNewAdmin.getEmail().isEmpty()) {
            String subject = "RoadWise - Administrative Succession Credentials";
            String body = "Hello " + savedNewAdmin.getFirstName() + ",\n\n" +
                    "Administrative authority for the RoadWise System has been formally transferred to you by " +
                    currentAdmin.getFirstName() + " " + currentAdmin.getLastName() + ".\n\n" +
                    "Login Credentials:\n" +
                    "• Username: " + savedNewAdmin.getUsername() + "\n" +
                    "• Temporary Password: " + tempPassword + "\n\n" +
                    "Please log in here: " + frontendUrl + "/login.html\n\n" +
                    "Please log in and update your security credentials.\n\n" +
                    "Best regards,\nRoadWise Administration - City of San Jose del Monte";
            try {
                emailService.sendEmail(savedNewAdmin.getEmail(), subject, body);
            } catch (Exception e) {
                System.err.println("Turnover email delivery failed: " + e.getMessage());
            }
        }

        // 8. RETURN RESPONSE
        Map<String, Object> responseData = new HashMap<>();
        responseData.put("message", "Administrative office successfully turned over!");
        responseData.put("successorName", savedNewAdmin.getFirstName() + " " + savedNewAdmin.getLastName());
        responseData.put("successorUsername", savedNewAdmin.getUsername());
        responseData.put("tempPassword", tempPassword);

        return ResponseEntity.ok(responseData);
    }

    // ==========================================
    // 10. CITY ENGINEER (CEO): FORMAL OFFICE TURNOVER
    // ==========================================
    @PostMapping("/handover-ceo")
    @PreAuthorize("hasAnyRole('ENGINEER', 'City Engineer')")
    @Transactional
    public ResponseEntity<?> handoverCeoOffice(
            @RequestBody Map<String, String> payload,
            HttpServletRequest request) {

        User caller = SecurityUtils.getCurrentUser();
        String currentCeoIdStr = payload.get("currentCeoId");
        String currentPassword = payload.get("currentPassword");
        String newFirstName = payload.get("firstName");
        String newMiddleName = payload.get("middleName");
        String newLastName = payload.get("lastName");
        String newEmail = payload.get("email");
        String newUsername = payload.get("username");
        String newPhone = payload.get("phoneNumber");
        String memoNumber = payload.get("memoNumber");

        if (currentCeoIdStr == null || currentPassword == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Current City Engineer credentials are required."));
        }

        Long currentCeoId = Long.valueOf(currentCeoIdStr);
        if (caller == null || !caller.getId().equals(currentCeoId)) {
            return ResponseEntity.status(403).body(Map.of("error", "You may only turn over your own City Engineer account."));
        }
        Optional<User> currentCeoOpt = userRepository.findById(currentCeoId);
        if (currentCeoOpt.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "Current City Engineer account not found."));
        }

        User currentCeo = currentCeoOpt.get();

        String ceoHandoverThrottle = "HANDOVER_CEO:" + currentCeo.getId();
        if (rateLimitService.isBlocked(ceoHandoverThrottle)) {
            return ResponseEntity.status(429)
                    .header("Retry-After", "60")
                    .body(Map.of("error", "Too many failed attempts. Please try again in a minute."));
        }

        // 🛡️ 1. SECURITY CHECK: Verify Outgoing CEO Password
        if (!verifyAndMigratePassword(currentCeo, currentPassword)) {
            rateLimitService.recordFailure(ceoHandoverThrottle);
            userRepository.save(currentCeo);
            activityLogService.log(
                    currentCeo,
                    "SECURITY",
                    "CEO_HANDOVER_UNAUTHORIZED",
                    "#USR-" + currentCeo.getId(),
                    "Unauthorized turnover attempt: Incorrect current password submitted.",
                    "FAILED",
                    request
            );
            return ResponseEntity.status(401).body(Map.of("error", "Incorrect current password. Turnover authorization rejected."));
        }
        rateLimitService.clear(ceoHandoverThrottle);

        // 🛡️ 2. UNIQUE CONFLICT CHECKS
        if (userRepository.findByUsername(newUsername).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Username '" + newUsername + "' is already taken."));
        }

        if (userRepository.findByEmail(newEmail).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Official email '" + newEmail + "' is already registered to another user."));
        }

        // 🛡️ 3. GENERATE SECURE TEMPORARY CREDENTIALS
        String tempPassword = generateTemporaryPassword();

        // 4. PROVISION INCOMING SUCCESSOR
        User newCeo = new User();
        newCeo.setFirstName(newFirstName != null ? newFirstName.trim() : "");
        newCeo.setMiddleName(newMiddleName != null ? newMiddleName.trim() : "");
        newCeo.setLastName(newLastName != null ? newLastName.trim() : "");
        newCeo.setEmail(newEmail != null ? newEmail.trim() : "");
        newCeo.setUsername(newUsername != null ? newUsername.trim() : "");
        newCeo.setPhoneNumber(newPhone != null ? newPhone.trim() : "");
        newCeo.setPassword(passwordEncoder.encode(tempPassword));
        newCeo.setPasswordChangeRequired(true);
        newCeo.setTempPasswordExpiresAt(LocalDateTime.now().plusHours(24));
        newCeo.setRole(currentCeo.getRole() != null ? currentCeo.getRole() : "ENGINEER");
        newCeo.setStatus("Active");
        newCeo.setProfilePicture("no_image.jpg");

        User savedNewCeo = userRepository.save(newCeo);

        // 5. DEACTIVATE OUTGOING PREDECESSOR (Preserve audit integrity)
        currentCeo.setStatus("Deactivated");
        revokeUserSessionsAndChallenges(currentCeo, "engineering office handover");
        userRepository.save(currentCeo);

        // 📋 6. WRITE SYSTEM ACTIVITY LOG
        String memoText = (memoNumber != null && !memoNumber.trim().isEmpty()) ? " [Memo Ref: " + memoNumber.trim() + "]" : "";
        activityLogService.log(
                currentCeo,
                "AUTH",
                "OFFICIAL_OFFICE_TURNOVER",
                "#USR-" + savedNewCeo.getId(),
                "City Engineer " + currentCeo.getFirstName() + " " + currentCeo.getLastName() +
                        " officially turned over engineering office to " + savedNewCeo.getFirstName() + " " + savedNewCeo.getLastName() + memoText + ".",
                "SUCCESS",
                request
        );

        // 📧 7. SEND SUCCESSION CREDENTIALS VIA EMAIL
        if (savedNewCeo.getEmail() != null && !savedNewCeo.getEmail().isEmpty()) {
            String subject = "RoadWise - City Engineer Succession Credentials";
            String body = "Hello " + savedNewCeo.getFirstName() + ",\n\n" +
                    "Engineering administration for the RoadWise System has been formally transferred to you by Engr. " +
                    currentCeo.getFirstName() + " " + currentCeo.getLastName() + ".\n\n" +
                    "Login Credentials:\n" +
                    "• Username: " + savedNewCeo.getUsername() + "\n" +
                    "• Temporary Password: " + tempPassword + "\n\n" +
                    "Please log in here: " + frontendUrl + "/login.html\n\n" +
                    "Please log in and update your security credentials.\n\n" +
                    "Best regards,\nCity Engineering Office - City of San Jose del Monte";
            try {
                emailService.sendEmail(savedNewCeo.getEmail(), subject, body);
            } catch (Exception e) {
                System.err.println("Turnover email delivery failed: " + e.getMessage());
            }
        }

        // 8. RETURN RESPONSE
        Map<String, Object> responseData = new HashMap<>();
        responseData.put("message", "City Engineering Office successfully turned over!");
        responseData.put("successorName", savedNewCeo.getFirstName() + " " + savedNewCeo.getLastName());
        responseData.put("successorUsername", savedNewCeo.getUsername());
        responseData.put("tempPassword", tempPassword);

        return ResponseEntity.ok(responseData);
    }

    // ==========================================
    // 11. VERIFIED EMAIL CHANGE (SELF-SERVICE)
    // ==========================================
    /**
     * Step 1: re-enter the current password, then the OTP is delivered to the
     * NEW address and the OLD address is notified. The real address does not
     * change until step 2 confirms that OTP, so stolen access tokens cannot
     * silently redirect recovery mail.
     */
    @PostMapping("/{id}/email-change/request")
    public ResponseEntity<?> requestEmailChange(
            @PathVariable Long id,
            @RequestBody Map<String, String> payload,
            HttpServletRequest request) {

        User caller = SecurityUtils.getCurrentUser();
        if (caller == null || !caller.getId().equals(id)) {
            return ResponseEntity.status(403).body(Map.of("error", "You may only change your own email address."));
        }
        Optional<User> userOpt = userRepository.findById(id);
        if (userOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        User user = userOpt.get();

        String passwordFailureKey = "EMAIL_CHANGE_PWD:" + id;
        if (rateLimitService.isBlocked(passwordFailureKey)) {
            return ResponseEntity.status(429)
                    .header("Retry-After", "60")
                    .body(Map.of("error", "Too many failed attempts. Please try again in a minute."));
        }

        String currentPassword = payload.get("currentPassword");
        if (!verifyAndMigratePassword(user, currentPassword)) {
            rateLimitService.recordFailure(passwordFailureKey);
            return ResponseEntity.status(401).body(Map.of("error", "Incorrect current password."));
        }
        rateLimitService.clear(passwordFailureKey);

        // Resend cooldown applies only once a password was accepted, so an
        // honest retry is never blocked by an earlier typo.
        String requestKey = "EMAIL_CHANGE:" + id;
        if (rateLimitService.isRequestThrottled(requestKey, Duration.ofSeconds(60), 5)) {
            return ResponseEntity.status(429)
                    .header("Retry-After", "60")
                    .body(Map.of("error", "A verification code was sent recently. Please wait a minute before requesting another."));
        }
        rateLimitService.recordRequest(requestKey);

        String newEmail = payload.get("email") == null ? "" : payload.get("email").trim();
        if (newEmail.isEmpty() || newEmail.equalsIgnoreCase(user.getEmail() == null ? "" : user.getEmail())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Provide a different email address."));
        }
        if (userRepository.findByEmail(newEmail).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("error", "That email is already registered to another user."));
        }

        String otp = String.format("%06d", SECURE_RANDOM.nextInt(1_000_000));
        user.setPendingEmail(newEmail);
        user.setEmailOtpHash(passwordEncoder.encode(otp));
        user.setEmailOtpExpiresAt(LocalDateTime.now().plusMinutes(10));
        user.setEmailOtpAttempts(0);
        userRepository.save(user);

        try {
            emailService.sendEmail(newEmail, "RoadWise - Confirm Your New Email",
                    "Hello " + user.getFirstName() + ",\n\n" +
                    "Your new email verification code is: " + otp + "\n\n" +
                    "It expires in 10 minutes. If you did not request this, ignore this email.");
        } catch (Exception e) {
            System.err.println("Email-change OTP delivery failed: " + e.getMessage());
        }
        if (user.getEmail() != null && !user.getEmail().isEmpty()) {
            try {
                emailService.sendEmail(user.getEmail(), "RoadWise - Email Change Requested",
                        "Hello " + user.getFirstName() + ",\n\n" +
                        "A request was made to change the email on your account to " + newEmail + ".\n\n" +
                        "If this was not you, contact the CPDO Administrator immediately.");
            } catch (Exception e) {
                System.err.println("Email-change notification delivery failed: " + e.getMessage());
            }
        }

        activityLogService.log(user, "USER", "EMAIL_CHANGE_REQUESTED", "#USR-" + user.getId(),
                "Email change requested; OTP sent to the new address.", "SUCCESS", request);

        // Same response shape whether or not the address was accepted, to avoid
        // leaking which addresses exist.
        return ResponseEntity.ok(Map.of("message", "If the address is valid, a verification code has been sent to it."));
    }

    /** Step 2: confirm the OTP that was delivered to the NEW address. */
    @PostMapping("/{id}/email-change/confirm")
    @Transactional
    public ResponseEntity<?> confirmEmailChange(
            @PathVariable Long id,
            @RequestBody Map<String, String> payload,
            HttpServletRequest request) {

        User caller = SecurityUtils.getCurrentUser();
        if (caller == null || !caller.getId().equals(id)) {
            return ResponseEntity.status(403).body(Map.of("error", "You may only change your own email address."));
        }
        Optional<User> userOpt = userRepository.findById(id);
        if (userOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        User user = userOpt.get();

        if (user.getPendingEmail() == null || user.getEmailOtpHash() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "No email change is pending."));
        }
        if (user.getEmailOtpExpiresAt() == null || LocalDateTime.now().isAfter(user.getEmailOtpExpiresAt())) {
            clearPendingEmailChange(user);
            userRepository.save(user);
            return ResponseEntity.badRequest().body(Map.of("error", "The verification code expired. Please request a new one."));
        }
        if (user.getEmailOtpAttempts() >= 5) {
            clearPendingEmailChange(user);
            userRepository.save(user);
            return ResponseEntity.status(429).header("Retry-After", "600")
                    .body(Map.of("error", "Maximum verification attempts exceeded. Please request a new code."));
        }

        String otp = payload.get("otp");
        if (otp == null || !passwordEncoder.matches(otp, user.getEmailOtpHash())) {
            user.setEmailOtpAttempts(user.getEmailOtpAttempts() + 1);
            boolean exhausted = user.getEmailOtpAttempts() >= 5;
            if (exhausted) {
                clearPendingEmailChange(user);
            }
            userRepository.save(user);
            return exhausted
                    ? ResponseEntity.status(429).header("Retry-After", "600")
                            .body(Map.of("error", "Maximum verification attempts exceeded. Please request a new code."))
                    : ResponseEntity.status(401).body(Map.of("error", "Invalid verification code."));
        }

        String oldEmail = user.getEmail();
        user.setEmail(user.getPendingEmail());
        clearPendingEmailChange(user);
        // Completing the change revokes every session and any pending
        // MFA/recovery challenge (they would still address the old inbox).
        revokeUserSessionsAndChallenges(user, "email address changed");
        userRepository.save(user);

        try {
            emailService.sendEmail(user.getEmail(), "RoadWise - Email Address Changed",
                    "The email on your RoadWise account is now " + user.getEmail() + ".\n\n" +
                    "You have been signed out for security; please log in again.");
        } catch (Exception e) {
            System.err.println("Email-change confirmation delivery failed: " + e.getMessage());
        }
        if (oldEmail != null && !oldEmail.isEmpty()) {
            try {
                emailService.sendEmail(oldEmail, "RoadWise - Email Address Changed",
                        "The email on your RoadWise account was changed to " + user.getEmail() + ".\n\n" +
                        "If you did not make this change, contact the CPDO Administrator immediately.");
            } catch (Exception e) {
                System.err.println("Email-change old-address notice failed: " + e.getMessage());
            }
        }

        activityLogService.log(user, "USER", "EMAIL_CHANGE_CONFIRMED", "#USR-" + user.getId(),
                "Email change verified via OTP; all sessions revoked.", "SUCCESS", request);

        return ResponseEntity.ok(Map.of("message", "Email address updated successfully. Please log in again."));
    }

    private void clearPendingEmailChange(User user) {
        user.setPendingEmail(null);
        user.setEmailOtpHash(null);
        user.setEmailOtpExpiresAt(null);
        user.setEmailOtpAttempts(0);
    }
}
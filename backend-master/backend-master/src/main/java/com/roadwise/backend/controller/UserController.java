package com.roadwise.backend.controller;

import com.roadwise.backend.dto.UserResponseDto;
import com.roadwise.backend.model.User;
import com.roadwise.backend.repository.BarangayRepository;
import com.roadwise.backend.repository.UserRepository;
import com.roadwise.backend.service.ActivityLogService;
import com.roadwise.backend.service.EmailService;
import com.roadwise.backend.service.NotificationService;
import com.roadwise.backend.service.SupabaseStorageService;
import com.roadwise.backend.util.PasswordPolicy;
import com.roadwise.backend.util.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
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

    // ==========================================
    // 🚀 HELPER: DYNAMICALLY FIND ADMIN ID
    // ==========================================
    private Long getAdminId() {
        return userRepository.findAll().stream()
                .filter(user -> user.getRole() != null && (user.getRole().equalsIgnoreCase("CPDO Admin") || user.getRole().equalsIgnoreCase("Admin")))
                .map(User::getId)
                .findFirst()
                .orElse(1L);
    }

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
        if (updates.containsKey("email")) user.setEmail(updates.get("email"));
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

        return ResponseEntity.ok(Map.of("message", "Profile updated successfully"));
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
            Optional<User> userOpt = userRepository.findById(id);
            if (userOpt.isEmpty()) return ResponseEntity.notFound().build();

            User user = userOpt.get();

            // ☁️ UPLOAD DIRECTLY TO SUPABASE OBJECT STORAGE
            String cloudAvatarUrl = supabaseStorageService.uploadImage(file);

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
    // 3. SECURE PASSWORD UPDATE (1-HOUR LOCKOUT)
    //    Lockout state is persisted on the user row (failed_login_attempts /
    //    lockout_until), consistent with the database-backed security model in
    //    AuthController. The old in-memory securityTracker map was removed:
    //    it leaked entries and reset on every restart / across instances.
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

        if (user.getLockoutUntil() != null && LocalDateTime.now().isBefore(user.getLockoutUntil())) {
            long minutesLeft = Duration.between(LocalDateTime.now(), user.getLockoutUntil()).toMinutes() + 1;
            return ResponseEntity.status(429).body(Map.of("error", "Security lockout active. Please try again in " + minutesLeft + " minute(s)."));
        }

        if (!verifyAndMigratePassword(user, currentPassword)) {
            int attempts = user.getFailedLoginAttempts() + 1;
            user.setFailedLoginAttempts(attempts);
            if (attempts >= 5) {
                user.setLockoutUntil(LocalDateTime.now().plusHours(1));
                userRepository.save(user);

                activityLogService.log(
                        user,
                        "AUTH",
                        "PASSWORD_CHANGE_LOCKED",
                        "#USR-" + user.getId(),
                        "Password update locked for 1 hour due to multiple incorrect password attempts.",
                        "WARNING",
                        request
                );

                return ResponseEntity.status(429).body(Map.of("error", "Maximum attempts reached! Account locked for 1 hour for security."));
            }
            userRepository.save(user);
            int remaining = 5 - attempts;
            return ResponseEntity.status(401).body(Map.of("error", "Incorrect current password. " + remaining + " attempt(s) remaining."));
        }

        String policyError = PasswordPolicy.validate(newPassword);
        if (policyError != null) {
            return ResponseEntity.badRequest().body(Map.of("error", policyError));
        }

        user.setFailedLoginAttempts(0);
        user.setLockoutUntil(null);
        user.setPassword(passwordEncoder.encode(newPassword));
        // Revoke every existing session, including the caller's current token.
        user.bumpSessionVersion();
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
    @PreAuthorize("hasAnyRole('ADMIN', 'CPDO Admin')")
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
    @PreAuthorize("hasAnyRole('ADMIN', 'CPDO Admin')")
    public ResponseEntity<?> registerOfficial(
            @RequestBody Map<String, String> payload,
            HttpServletRequest request) {

        String username = payload.get("username");
        String rawPassword = payload.get("password");

        if (username == null || username.trim().isEmpty()) {
            return ResponseEntity.status(400).body(Map.of("error", "Username is required."));
        }
        if (rawPassword == null || rawPassword.trim().isEmpty()) {
            return ResponseEntity.status(400).body(Map.of("error", "Password is required."));
        }
<<<<<<< HEAD
        String policyError = PasswordPolicy.validate(rawPassword);
        if (policyError != null) {
            return ResponseEntity.status(400).body(Map.of("error", policyError));
        }
=======
>>>>>>> 04ae5226e73d3211b8bdff7e0567d67c856aca26
        if (userRepository.findByUsername(username).isPresent()) {
            return ResponseEntity.status(400).body(Map.of("error", "Username already exists!"));
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

        String adminIdStr = payload.get("adminId");
        Long adminId = (adminIdStr != null && !adminIdStr.isEmpty()) ? Long.valueOf(adminIdStr) : getAdminId();
        User adminActor = userRepository.findById(adminId).orElse(null);

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
    @PreAuthorize("hasAnyRole('ADMIN', 'CPDO Admin')")
    public ResponseEntity<?> manageUserRecord(
            @PathVariable Long id,
            @RequestBody Map<String, String> updates,
            HttpServletRequest request) {

        Optional<User> userOpt = userRepository.findById(id);
        if (userOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        User user = userOpt.get();
        user.setFirstName(updates.get("firstName"));
        user.setMiddleName(updates.get("middleName"));
        user.setLastName(updates.get("lastName"));
        user.setEmail(updates.get("email"));

        String oldStatus = user.getStatus();
        String newStatus = updates.get("status");

        if (newStatus != null && !newStatus.isEmpty()) {
            user.setStatus(newStatus);

            if (oldStatus != null && !oldStatus.equals(newStatus)) {
                notificationService.sendNotification(
                        user.getId(),
                        "Account Status Update",
                        "Your account status has been updated to: " + newStatus + ".",
                        "ACCOUNT"
                );
            }
        }

        if (updates.get("barangayId") != null && !updates.get("barangayId").isEmpty()) {
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
        }

        userRepository.save(user);

        String adminIdStr = updates.get("adminId");
        Long adminId = (adminIdStr != null && !adminIdStr.isEmpty()) ? Long.valueOf(adminIdStr) : getAdminId();
        User adminActor = userRepository.findById(adminId).orElse(null);

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
    @PreAuthorize("hasAnyRole('ADMIN', 'CPDO Admin')")
    public ResponseEntity<?> emergencyPasswordReset(
            @PathVariable Long id,
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
        user.setFailedLoginAttempts(0);
        user.setLockoutUntil(null);
<<<<<<< HEAD
        // Force every existing session for the affected account to re-authenticate.
        user.bumpSessionVersion();
=======
>>>>>>> 04ae5226e73d3211b8bdff7e0567d67c856aca26
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
        if (adminActor == null) {
            adminActor = userRepository.findById(getAdminId()).orElse(null);
        }

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
    @PreAuthorize("hasAnyRole('ADMIN', 'CPDO Admin')")
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

        // 🛡️ 1. SECURITY CHECK: Verify Outgoing Admin Password (BCrypt-aware)
        if (!verifyAndMigratePassword(currentAdmin, currentPassword)) {
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
        newAdmin.setRole("CPDO Admin");
        newAdmin.setStatus("Active");
        newAdmin.setFailedLoginAttempts(0);
        newAdmin.setProfilePicture("no_image.jpg");

        User savedNewAdmin = userRepository.save(newAdmin);

        // 5. DEACTIVATE OUTGOING PREDECESSOR (Preserve audit integrity)
        currentAdmin.setStatus("Deactivated");
        currentAdmin.bumpSessionVersion();
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

        // 🛡️ 1. SECURITY CHECK: Verify Outgoing CEO Password
        if (!verifyAndMigratePassword(currentCeo, currentPassword)) {
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
        newCeo.setRole(currentCeo.getRole() != null ? currentCeo.getRole() : "ENGINEER");
        newCeo.setStatus("Active");
        newCeo.setFailedLoginAttempts(0);
        newCeo.setProfilePicture("no_image.jpg");

        User savedNewCeo = userRepository.save(newCeo);

        // 5. DEACTIVATE OUTGOING PREDECESSOR (Preserve audit integrity)
        currentCeo.setStatus("Deactivated");
        currentCeo.bumpSessionVersion();
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
}
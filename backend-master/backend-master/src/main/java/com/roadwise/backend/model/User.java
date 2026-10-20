package com.roadwise.backend.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Optimistic locking: a stale save (slow avatar upload, concurrent admin
    // edit) must fail with 409 rather than silently overwriting a newer
    // password, status or session stamp.
    @Version
    @JsonIgnore
    private Long version;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(nullable = false)
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;

    @Column(nullable = false)
    private String role; // "ADMIN", "ENGINEER", or "BARANGAY"

    // 🚀 NAMES
    private String firstName;
    private String middleName;
    private String lastName;

    // 🚀 PROFILE DETAILS
    private String email;
    private String phoneNumber;
    private LocalDate birthday;
    private String gender;

    // 🚀 PROFILE PICTURE & STATUS
    private String profilePicture;
    private String status;



    // Security stamp embedded in every access token (claim "sv"). Incrementing
    // it revokes all previously issued tokens for this account immediately,
    // without waiting for the 15-minute access-token expiry.
    @Column(name = "session_version", nullable = false)
    @JsonIgnore
    private Long sessionVersion = 0L;

    // Unverified email-change request. The real address only changes after the
    // OTP sent to the NEW address is confirmed.
    @Column(name = "pending_email")
    @JsonIgnore
    private String pendingEmail;

    @Column(name = "email_otp_hash")
    @JsonIgnore
    private String emailOtpHash;

    @Column(name = "email_otp_expires_at")
    @JsonIgnore
    private LocalDateTime emailOtpExpiresAt;

    @Column(name = "email_otp_attempts")
    @JsonIgnore
    private Integer emailOtpAttempts = 0;

    @Column(name = "password_change_required", nullable = false)
    private Boolean passwordChangeRequired = false;

    @Column(name = "temp_password_expires_at")
    @JsonIgnore
    private LocalDateTime tempPasswordExpiresAt;

    // Which factors this account accepts. Kept in sync with real enrolment by
    // MfaPolicyService: EMAIL_OTP while email is the only usable factor, ANY_ONE
    // once a second factor is active. V16 defaults it for pre-existing accounts,
    // so no account gains a new requirement it has not opted into.
    @Column(name = "mfa_policy", nullable = false, length = 32)
    @JsonIgnore
    private String mfaPolicy = "EMAIL_OTP";

    // Nullable for CPDO and CEO accounts
    @ManyToOne
    @JoinColumn(name = "barangay_id", nullable = true)
    private Barangay barangay;

    public User() {
    }

    // --- GETTERS AND SETTERS ---
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public Barangay getBarangay() { return barangay; }
    public void setBarangay(Barangay barangay) { this.barangay = barangay; }

    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }

    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }

    public String getMiddleName() { return middleName; }
    public void setMiddleName(String middleName) { this.middleName = middleName; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }

    public LocalDate getBirthday() { return birthday; }
    public void setBirthday(LocalDate birthday) { this.birthday = birthday; }

    public String getGender() { return gender; }
    public void setGender(String gender) { this.gender = gender; }

    public String getProfilePicture() { return profilePicture; }
    public void setProfilePicture(String profilePicture) { this.profilePicture = profilePicture; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }



    @JsonIgnore
    public Long getSessionVersion() { return sessionVersion == null ? 0L : sessionVersion; }

    public void setSessionVersion(Long sessionVersion) { this.sessionVersion = sessionVersion; }

    public String getPendingEmail() { return pendingEmail; }
    public void setPendingEmail(String pendingEmail) { this.pendingEmail = pendingEmail; }

    public String getEmailOtpHash() { return emailOtpHash; }
    public void setEmailOtpHash(String emailOtpHash) { this.emailOtpHash = emailOtpHash; }

    public LocalDateTime getEmailOtpExpiresAt() { return emailOtpExpiresAt; }
    public void setEmailOtpExpiresAt(LocalDateTime emailOtpExpiresAt) { this.emailOtpExpiresAt = emailOtpExpiresAt; }

    public Integer getEmailOtpAttempts() { return emailOtpAttempts == null ? 0 : emailOtpAttempts; }
    public void setEmailOtpAttempts(Integer emailOtpAttempts) { this.emailOtpAttempts = emailOtpAttempts; }

    @JsonIgnore
    public String getMfaPolicy() { return mfaPolicy == null ? "EMAIL_OTP" : mfaPolicy; }

    public void setMfaPolicy(String mfaPolicy) { this.mfaPolicy = mfaPolicy; }

    public Boolean getPasswordChangeRequired() { return passwordChangeRequired != null && passwordChangeRequired; }
    public void setPasswordChangeRequired(Boolean passwordChangeRequired) { this.passwordChangeRequired = passwordChangeRequired; }

    public LocalDateTime getTempPasswordExpiresAt() { return tempPasswordExpiresAt; }
    public void setTempPasswordExpiresAt(LocalDateTime tempPasswordExpiresAt) { this.tempPasswordExpiresAt = tempPasswordExpiresAt; }

    public Long getVersion() { return version; }

    /** Increments the security stamp, revoking all existing tokens. */
    public void bumpSessionVersion() {
        this.sessionVersion = getSessionVersion() + 1;
    }

}
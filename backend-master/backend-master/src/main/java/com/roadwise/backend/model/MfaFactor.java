package com.roadwise.backend.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;

/**
 * One enrolled authentication factor belonging to a user.
 *
 * <p>A row starts life {@code PENDING} during enrolment and only becomes
 * {@code ACTIVE} once the user has proved they can generate a valid code from it.
 * That two-step shape is what stops a mistyped secret from locking someone out of
 * their own account.
 *
 * <p>Timestamps use {@link LocalDateTime#now()} to match the existing MFA and
 * refresh-token handling, which also compares against {@code LocalDateTime.now()}.
 * Switching any one of these to an explicit zone without migrating the rest would
 * introduce an offset bug in those comparisons.
 */
@Entity
@Table(name = "user_mfa_factors")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class MfaFactor {

    public static final String TYPE_EMAIL_OTP = "EMAIL_OTP";
    public static final String TYPE_TOTP = "TOTP";
    public static final String TYPE_WEBAUTHN = "WEBAUTHN";

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";


    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JsonIgnore
    private User user;

    @Column(name = "factor_type", nullable = false, length = 32)
    private String factorType;

    @Column(length = 100)
    private String label;

    @Column(nullable = false, length = 24)
    private String status = STATUS_PENDING;

    /**
     * AES-GCM envelope of the shared secret. Never serialised: the API surface
     * exposes a factor's existence, never its key material.
     */
    @Column(name = "secret_ciphertext")
    @JsonIgnore
    private String secretCiphertext;

    @Column(name = "secret_key_version")
    @JsonIgnore
    private Integer secretKeyVersion;

    // --- Passkey (WebAuthn) credential material, for factor_type = 'WEBAUTHN' ---
    //
    // Populated only for passkeys; every field here is null or zero for TOTP.

    /** Raw credential id, unique across the table. Cleared when the factor is disabled. */
    @Column(name = "credential_id")
    @JsonIgnore
    private byte[] credentialId;

    /** COSE-encoded public key. Verification material, so never serialised outward. */
    @Column(name = "public_key")
    @JsonIgnore
    private byte[] publicKey;

    /**
     * Signature counter reported by the last assertion. A counter that goes backwards
     * is the spec's clone signal, so this has to be persisted to be checked at all.
     */
    @Column(name = "signature_count", nullable = false)
    private long signatureCount;

    @Column(name = "uv_initialized", nullable = false)
    @JsonIgnore
    private boolean uvInitialized;

    @Column(name = "backup_eligible")
    @JsonIgnore
    private Boolean backupEligible;

    @Column(name = "backup_state")
    @JsonIgnore
    private Boolean backupState;

    /** Comma-separated {@code AuthenticatorTransport} names, stored as reported. */
    @Column(name = "transports", length = 255)
    @JsonIgnore
    private String transports;

    @Column(name = "attestation_object")
    @JsonIgnore
    private byte[] attestationObject;

    @Column(name = "attestation_client_data_json")
    @JsonIgnore
    private byte[] attestationClientDataJson;

    /** Time-step of the last accepted TOTP code, for replay refusal. */
    @Column(name = "last_totp_step")
    @JsonIgnore
    private Long lastTotpStep;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    @Column(name = "disabled_at")
    @JsonIgnore
    private LocalDateTime disabledAt;

    public MfaFactor() {
    }

    public MfaFactor(User user, String factorType, String label, String status) {
        this.user = user;
        this.factorType = factorType;
        this.label = label;
        this.status = status;
    }

    /** @return true when this factor is currently usable for a login challenge. */
    public boolean isActive() {
        return STATUS_ACTIVE.equals(status);
    }

    /**
     * Discards every piece of key material this factor holds.
     *
     * <p>Lives on the entity because it is an invariant, not a caller's concern: a
     * factor that cannot be used must not retain material anyone reaching the row could
     * still decrypt or replay. Centralising it here is a direct response to a real
     * defect -- disabling a passkey through the management flow left its credential id
     * and public key in place, because only the credential adapter knew to clear them.
     */
    public void clearKeyMaterial() {
        // TOTP
        this.secretCiphertext = null;
        this.secretKeyVersion = null;
        this.lastTotpStep = null;
        // WebAuthn. Clearing the credential id also frees the unique index, so the same
        // authenticator can be enrolled again later.
        this.credentialId = null;
        this.publicKey = null;
        this.attestationObject = null;
        this.attestationClientDataJson = null;
        this.signatureCount = 0L;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }

    public String getFactorType() { return factorType; }
    public void setFactorType(String factorType) { this.factorType = factorType; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getSecretCiphertext() { return secretCiphertext; }
    public void setSecretCiphertext(String secretCiphertext) { this.secretCiphertext = secretCiphertext; }

    public Integer getSecretKeyVersion() { return secretKeyVersion; }
    public void setSecretKeyVersion(Integer secretKeyVersion) { this.secretKeyVersion = secretKeyVersion; }

    public Long getLastTotpStep() { return lastTotpStep; }
    public void setLastTotpStep(Long lastTotpStep) { this.lastTotpStep = lastTotpStep; }

    public byte[] getCredentialId() { return credentialId; }
    public void setCredentialId(byte[] credentialId) { this.credentialId = credentialId; }

    public byte[] getPublicKey() { return publicKey; }
    public void setPublicKey(byte[] publicKey) { this.publicKey = publicKey; }

    public long getSignatureCount() { return signatureCount; }
    public void setSignatureCount(long signatureCount) { this.signatureCount = signatureCount; }

    public boolean isUvInitialized() { return uvInitialized; }
    public void setUvInitialized(boolean uvInitialized) { this.uvInitialized = uvInitialized; }

    public Boolean getBackupEligible() { return backupEligible; }
    public void setBackupEligible(Boolean backupEligible) { this.backupEligible = backupEligible; }

    public Boolean getBackupState() { return backupState; }
    public void setBackupState(Boolean backupState) { this.backupState = backupState; }

    public String getTransports() { return transports; }
    public void setTransports(String transports) { this.transports = transports; }

    public byte[] getAttestationObject() { return attestationObject; }
    public void setAttestationObject(byte[] attestationObject) { this.attestationObject = attestationObject; }

    public byte[] getAttestationClientDataJson() { return attestationClientDataJson; }
    public void setAttestationClientDataJson(byte[] attestationClientDataJson) { this.attestationClientDataJson = attestationClientDataJson; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(LocalDateTime lastUsedAt) { this.lastUsedAt = lastUsedAt; }

    public LocalDateTime getDisabledAt() { return disabledAt; }
    public void setDisabledAt(LocalDateTime disabledAt) { this.disabledAt = disabledAt; }
}

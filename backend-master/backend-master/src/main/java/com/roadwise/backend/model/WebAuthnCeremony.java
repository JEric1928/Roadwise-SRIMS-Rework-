package com.roadwise.backend.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * One in-flight WebAuthn ceremony: a registration or an authentication whose options
 * have been issued but whose response has not yet been verified.
 *
 * <p>Addressed by the digest of a random ceremony ID, never by username or credential
 * ID. Bound explicitly to either the authenticated user (registration) or the
 * pre-authentication MFA challenge (assertion) -- the lookup key and the ownership
 * binding are deliberately different columns.
 */
@Entity
@Table(name = "webauthn_ceremony_state")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class WebAuthnCeremony {

    public static final String TYPE_REGISTRATION = "REGISTRATION";
    public static final String TYPE_AUTHENTICATION = "AUTHENTICATION";

    public static final String STATUS_ISSUED = "ISSUED";
    public static final String STATUS_CONSUMED = "CONSUMED";

    @Id
    @Column(name = "ceremony_key_hash")
    private String ceremonyKeyHash;

    @Column(name = "ceremony_type", nullable = false, length = 32)
    private String ceremonyType;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "user_handle")
    @JsonIgnore
    private byte[] userHandle;

    @Column(name = "mfa_challenge_id")
    private Long mfaChallengeId;

    /**
     * The serialised options, including the one-time WebAuthn challenge. Cleared the
     * moment the ceremony is claimed: afterwards only bindings, timestamps and outcome
     * remain, for the audit window.
     */
    // columnDefinition matches V19's TEXT: without it Hibernate's default (varchar 255)
    // truncates real options under a generated schema. PostgreSQL uses the migration;
    // the explicit type keeps both schemas saying the same thing.
    @Column(name = "options_json", columnDefinition = "text")
    @JsonIgnore
    private String optionsJson;

    @Column(nullable = false, length = 24)
    private String status = STATUS_ISSUED;

    @Column(length = 32)
    private String outcome;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "consumed_at")
    private LocalDateTime consumedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public WebAuthnCeremony() {
    }

    public boolean isLive() {
        return STATUS_ISSUED.equals(status)
                && expiresAt != null
                && LocalDateTime.now().isBefore(expiresAt);
    }

    public String getCeremonyKeyHash() { return ceremonyKeyHash; }
    public void setCeremonyKeyHash(String ceremonyKeyHash) { this.ceremonyKeyHash = ceremonyKeyHash; }

    public String getCeremonyType() { return ceremonyType; }
    public void setCeremonyType(String ceremonyType) { this.ceremonyType = ceremonyType; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public byte[] getUserHandle() { return userHandle; }
    public void setUserHandle(byte[] userHandle) { this.userHandle = userHandle; }

    public Long getMfaChallengeId() { return mfaChallengeId; }
    public void setMfaChallengeId(Long mfaChallengeId) { this.mfaChallengeId = mfaChallengeId; }

    public String getOptionsJson() { return optionsJson; }
    public void setOptionsJson(String optionsJson) { this.optionsJson = optionsJson; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getOutcome() { return outcome; }
    public void setOutcome(String outcome) { this.outcome = outcome; }

    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }

    public LocalDateTime getConsumedAt() { return consumedAt; }
    public void setConsumedAt(LocalDateTime consumedAt) { this.consumedAt = consumedAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}

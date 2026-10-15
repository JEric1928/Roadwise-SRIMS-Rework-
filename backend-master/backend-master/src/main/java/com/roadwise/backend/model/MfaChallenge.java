package com.roadwise.backend.model;

import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import java.time.LocalDateTime;

@Entity
@Table(name = "mfa_challenges")
public class MfaChallenge {

    /**
     * Attempt budget for a login challenge. Shared by wrong codes and by factor
     * re-selection, so switching factors cannot buy a fresh guessing budget.
     */
    public static final int MAX_ATTEMPTS = 5;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    /**
     * BCrypt hash of the emailed OTP. Null while the challenge is still waiting for
     * the user to pick a factor: the row is created as soon as the password is
     * accepted, which is before any code exists.
     */
    private String otpCodeHash;

    /**
     * SHA-256 of the single-use token handed to the client when the password is
     * accepted. This is what binds a challenge to the login attempt that created it,
     * replacing the old scheme where {@code /verify-mfa} trusted a client-supplied
     * user id to find "the most recent unused challenge".
     */
    @Column(name = "challenge_token_hash")
    private String challengeTokenHash;

    /**
     * Which factor this challenge answers for. Null means the user has not chosen one
     * yet; a challenge may only be verified once this is set.
     */
    @Column(name = "factor_type", length = 32)
    private String factorType;

    /** The enrolled factor being used, when the challenge targets a stored factor. */
    @Column(name = "factor_id")
    private Long factorId;

    private int attempts = 0;

    private int maxAttempts = MAX_ATTEMPTS;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    private boolean used = false;

    private LocalDateTime createdAt = LocalDateTime.now();

    public MfaChallenge() {}

    public MfaChallenge(User user, String otpCodeHash, LocalDateTime expiresAt) {
        this.user = user;
        this.otpCodeHash = otpCodeHash;
        this.expiresAt = expiresAt;
    }

    /**
     * Opens a factor-agnostic challenge tied to a freshly minted token. The factor is
     * filled in later by {@code /mfa/select} (or immediately by {@code /login} when
     * the account has exactly one option, preserving the single-step flow).
     */
    public static MfaChallenge forToken(User user, String challengeTokenHash, LocalDateTime expiresAt) {
        MfaChallenge challenge = new MfaChallenge();
        challenge.user = user;
        challenge.challengeTokenHash = challengeTokenHash;
        challenge.expiresAt = expiresAt;
        challenge.maxAttempts = MAX_ATTEMPTS;
        return challenge;
    }

    /** @return true once a factor has been chosen and a code can be checked. */
    public boolean isFactorSelected() {
        return factorType != null;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }

    public String getOtpCodeHash() { return otpCodeHash; }
    public void setOtpCodeHash(String otpCodeHash) { this.otpCodeHash = otpCodeHash; }

    public String getChallengeTokenHash() { return challengeTokenHash; }
    public void setChallengeTokenHash(String challengeTokenHash) { this.challengeTokenHash = challengeTokenHash; }

    public String getFactorType() { return factorType; }
    public void setFactorType(String factorType) { this.factorType = factorType; }

    public Long getFactorId() { return factorId; }
    public void setFactorId(Long factorId) { this.factorId = factorId; }

    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }

    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }

    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }

    public boolean isUsed() { return used; }
    public void setUsed(boolean used) { this.used = used; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}

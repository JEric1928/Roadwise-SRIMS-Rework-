package com.roadwise.backend.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "mfa_challenges")
public class MfaChallenge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String otpCodeHash;

    private int attempts = 0;

    private int maxAttempts = 3;

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

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }

    public String getOtpCodeHash() { return otpCodeHash; }
    public void setOtpCodeHash(String otpCodeHash) { this.otpCodeHash = otpCodeHash; }

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

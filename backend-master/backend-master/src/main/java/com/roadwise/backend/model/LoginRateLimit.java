package com.roadwise.backend.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "login_rate_limits")
public class LoginRateLimit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String keyIdentifier;

    private int attemptCount = 0;

    private LocalDateTime blockedUntil;

    private LocalDateTime firstAttemptAt = LocalDateTime.now();

    private LocalDateTime lastAttemptAt = LocalDateTime.now();

    public LoginRateLimit() {}

    public LoginRateLimit(String keyIdentifier) {
        this.keyIdentifier = keyIdentifier;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getKeyIdentifier() { return keyIdentifier; }
    public void setKeyIdentifier(String keyIdentifier) { this.keyIdentifier = keyIdentifier; }

    public int getAttemptCount() { return attemptCount; }
    public void setAttemptCount(int attemptCount) { this.attemptCount = attemptCount; }

    public LocalDateTime getBlockedUntil() { return blockedUntil; }
    public void setBlockedUntil(LocalDateTime blockedUntil) { this.blockedUntil = blockedUntil; }

    public LocalDateTime getFirstAttemptAt() { return firstAttemptAt; }
    public void setFirstAttemptAt(LocalDateTime firstAttemptAt) { this.firstAttemptAt = firstAttemptAt; }

    public LocalDateTime getLastAttemptAt() { return lastAttemptAt; }
    public void setLastAttemptAt(LocalDateTime lastAttemptAt) { this.lastAttemptAt = lastAttemptAt; }
}

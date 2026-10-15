package com.roadwise.backend.model;

import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import java.time.LocalDateTime;

@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    @Column(nullable = false, unique = true)
    private String tokenHash;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    private boolean revoked = false;

    private String replacedByTokenHash;

    private LocalDateTime createdAt = LocalDateTime.now();

    /**
     * When the human last actually authenticated for this session.
     *
     * <p>Set once at login and inherited by every rotation, so it always answers "when
     * did someone prove who they are" rather than "when was this row written". Refresh
     * must not move it: a rotated token that stamped a new time would let a caller
     * holding only refresh tokens satisfy a freshness check forever. NULL means the
     * session was never elevated and is treated as such.
     */
    @Column(name = "authenticated_at")
    private LocalDateTime authenticatedAt;

    private String createdByIp;

    // Absolute lifetime of the login session (does not extend on rotation).
    private LocalDateTime sessionExpiresAt;

    // Last successful refresh; drives the inactivity window.
    private LocalDateTime lastUsedAt;

    // Session version this token was issued under. Rotation is refused when the
    // account's stamp has moved on (credential change, logout, reuse event).
    @Column(name = "session_version", nullable = false)
    private Long sessionVersion = 0L;

    public RefreshToken() {}

    public RefreshToken(User user, String tokenHash, LocalDateTime expiresAt, String createdByIp) {
        this(user, tokenHash, expiresAt, createdByIp, null);
    }

    public RefreshToken(User user, String tokenHash, LocalDateTime expiresAt, String createdByIp,
                        LocalDateTime sessionExpiresAt) {
        this.user = user;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.createdByIp = createdByIp;
        this.sessionExpiresAt = sessionExpiresAt;
        this.lastUsedAt = LocalDateTime.now();
        this.sessionVersion = user != null ? user.getSessionVersion() : 0L;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }

    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }

    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }

    public boolean isRevoked() { return revoked; }
    public void setRevoked(boolean revoked) { this.revoked = revoked; }

    public String getReplacedByTokenHash() { return replacedByTokenHash; }
    public void setReplacedByTokenHash(String replacedByTokenHash) { this.replacedByTokenHash = replacedByTokenHash; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getAuthenticatedAt() { return authenticatedAt; }
    public void setAuthenticatedAt(LocalDateTime authenticatedAt) { this.authenticatedAt = authenticatedAt; }

    public String getCreatedByIp() { return createdByIp; }
    public void setCreatedByIp(String createdByIp) { this.createdByIp = createdByIp; }

    public LocalDateTime getSessionExpiresAt() { return sessionExpiresAt; }
    public void setSessionExpiresAt(LocalDateTime sessionExpiresAt) { this.sessionExpiresAt = sessionExpiresAt; }

    public LocalDateTime getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(LocalDateTime lastUsedAt) { this.lastUsedAt = lastUsedAt; }

    public Long getSessionVersion() { return sessionVersion == null ? 0L : sessionVersion; }
    public void setSessionVersion(Long sessionVersion) { this.sessionVersion = sessionVersion; }
}

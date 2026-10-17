package com.roadwise.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * A time-boxed, self-granted exception to the peer-rank rule.
 *
 * <p>Mirrors {@code V24__add_break_glass_grants.sql}; {@code ddl-auto=validate} compares the
 * two at startup. Unlike {@code privileged_action_request} this row is mutable on purpose --
 * the review fields are filled in afterwards.
 */
@Entity
@Table(name = "break_glass_grant")
public class BreakGlassGrant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "granted_to", nullable = false)
    private Long grantedTo;

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(name = "granted_at", nullable = false)
    private LocalDateTime grantedAt = LocalDateTime.now();

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "review_note", length = 500)
    private String reviewNote;

    public BreakGlassGrant() {
    }

    public BreakGlassGrant(Long grantedTo, String reason, LocalDateTime expiresAt) {
        this.grantedTo = grantedTo;
        this.reason = reason;
        this.expiresAt = expiresAt;
    }

    public boolean isLive(LocalDateTime now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getGrantedTo() { return grantedTo; }
    public void setGrantedTo(Long grantedTo) { this.grantedTo = grantedTo; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public LocalDateTime getGrantedAt() { return grantedAt; }
    public void setGrantedAt(LocalDateTime grantedAt) { this.grantedAt = grantedAt; }

    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }

    public LocalDateTime getRevokedAt() { return revokedAt; }
    public void setRevokedAt(LocalDateTime revokedAt) { this.revokedAt = revokedAt; }

    public Long getReviewedBy() { return reviewedBy; }
    public void setReviewedBy(Long reviewedBy) { this.reviewedBy = reviewedBy; }

    public LocalDateTime getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(LocalDateTime reviewedAt) { this.reviewedAt = reviewedAt; }

    public String getReviewNote() { return reviewNote; }
    public void setReviewNote(String reviewNote) { this.reviewNote = reviewNote; }
}

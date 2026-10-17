package com.roadwise.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * One administrative action waiting on a second administrator's agreement.
 *
 * <p>Column names and lengths mirror {@code V22__add_privileged_action_approvals.sql}
 * exactly; {@code spring.jpa.hibernate.ddl-auto=validate} compares the two at startup, so a
 * change here without a migration is a boot failure rather than a silent difference.
 *
 * <p>Rows are never updated outside the transitions the repository guards: PENDING to
 * APPROVED/DENIED by one approver, and APPROVED to EXECUTED by exactly one redemption.
 */
@Entity
@Table(name = "privileged_action_request")
public class PrivilegedActionRequest {

    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String DENIED = "DENIED";
    public static final String EXECUTED = "EXECUTED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "permission_key", nullable = false, length = 100)
    private String permissionKey;

    @Column(name = "target_user_id", nullable = false)
    private Long targetUserId;

    @Column(name = "requested_by", nullable = false)
    private Long requestedBy;

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(nullable = false, length = 16)
    private String status = PENDING;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt = LocalDateTime.now();

    @Column(name = "decided_by")
    private Long decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "decision_note", length = 500)
    private String decisionNote;

    @Column(name = "executed_at")
    private LocalDateTime executedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    public PrivilegedActionRequest() {
    }

    public PrivilegedActionRequest(String permissionKey, Long targetUserId, Long requestedBy,
                                   String reason, LocalDateTime expiresAt) {
        this.permissionKey = permissionKey;
        this.targetUserId = targetUserId;
        this.requestedBy = requestedBy;
        this.reason = reason;
        this.expiresAt = expiresAt;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getPermissionKey() { return permissionKey; }
    public void setPermissionKey(String permissionKey) { this.permissionKey = permissionKey; }

    public Long getTargetUserId() { return targetUserId; }
    public void setTargetUserId(Long targetUserId) { this.targetUserId = targetUserId; }

    public Long getRequestedBy() { return requestedBy; }
    public void setRequestedBy(Long requestedBy) { this.requestedBy = requestedBy; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public LocalDateTime getRequestedAt() { return requestedAt; }
    public void setRequestedAt(LocalDateTime requestedAt) { this.requestedAt = requestedAt; }

    public Long getDecidedBy() { return decidedBy; }
    public void setDecidedBy(Long decidedBy) { this.decidedBy = decidedBy; }

    public LocalDateTime getDecidedAt() { return decidedAt; }
    public void setDecidedAt(LocalDateTime decidedAt) { this.decidedAt = decidedAt; }

    public String getDecisionNote() { return decisionNote; }
    public void setDecisionNote(String decisionNote) { this.decisionNote = decisionNote; }

    public LocalDateTime getExecutedAt() { return executedAt; }
    public void setExecutedAt(LocalDateTime executedAt) { this.executedAt = executedAt; }

    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
}

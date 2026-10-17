package com.roadwise.backend.service;

import com.roadwise.backend.model.PrivilegedActionRequest;
import com.roadwise.backend.model.User;
import com.roadwise.backend.repository.AuthzRepository;
import com.roadwise.backend.repository.PrivilegedActionRequestRepository;
import com.roadwise.backend.repository.UserRepository;
import com.roadwise.backend.util.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Maker-checker for the actions rank-based authorization refuses.
 *
 * <p>{@code AuthorizationService.canActOn} denies an administrator any action on a peer or a
 * superior. That is the right default, but it is not the whole business: one of two peer
 * administrators occasionally has to act on the other. This service is the escape hatch --
 * the maker records why, a <em>different</em> administrator with the same permission agrees,
 * and the maker then runs the action once with the approval in hand.
 *
 * <p>What the control buys: a single stolen administrative session can no longer reach a
 * peer account, because the second half of the action needs somebody else's session. What it
 * does not buy: protection against two colluding administrators, which no in-application
 * control can provide.
 *
 * <p>Approval is not a blanket elevation. The redeemed approval is bound to one permission,
 * one target account and the account that asked for it, is usable once, and expires.
 */
@Service
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);

    /** How long a request may wait for a decision, and how long an approval stays redeemable. */
    public static final Duration LIFETIME = Duration.ofHours(24);

    /** The permission that gates approving somebody else's privileged request. */
    public static final String APPROVER_PERMISSION = "user:approve";

    private final PrivilegedActionRequestRepository requests;
    private final UserRepository users;
    private final AuthzRepository authz;
    private final ActivityLogService activityLog;

    public ApprovalService(PrivilegedActionRequestRepository requests,
                           UserRepository users,
                           AuthzRepository authz,
                           ActivityLogService activityLog) {
        this.requests = requests;
        this.users = users;
        this.authz = authz;
        this.activityLog = activityLog;
    }

    /**
     * Records that the caller wants to perform {@code permission} on {@code targetUserId}.
     *
     * <p>Reuses an open request rather than creating duplicates: a maker who retries because
     * the first response was lost should hold one ticket, not three that each need approving.
     */
    @Transactional
    public PrivilegedActionRequest request(String permission, Long targetUserId, String reason,
                                           HttpServletRequest http) {
        Long callerId = requireCaller();
        if (permission == null || permission.isBlank()) {
            throw new IllegalArgumentException("A permission is required.");
        }
        if (!authz.permissionsFor(callerId).contains(permission)) {
            throw new IllegalArgumentException("You do not hold '" + permission + "'.");
        }
        if (targetUserId == null || users.findById(targetUserId).isEmpty()) {
            throw new IllegalArgumentException("No such account.");
        }
        if (callerId.equals(targetUserId)) {
            throw new IllegalArgumentException("Your own account does not need an approval.");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A reason is required: an approval nobody can explain is not a control.");
        }

        PrivilegedActionRequest open = openRequest(permission, targetUserId, callerId,
                PrivilegedActionRequest.PENDING);
        if (open != null) {
            return open;
        }
        PrivilegedActionRequest approved = openRequest(permission, targetUserId, callerId,
                PrivilegedActionRequest.APPROVED);
        if (approved != null && approved.getExpiresAt().isAfter(LocalDateTime.now())) {
            return approved;
        }

        PrivilegedActionRequest created = requests.save(new PrivilegedActionRequest(
                permission, targetUserId, callerId, reason.trim(), LocalDateTime.now().plus(LIFETIME)));

        audit(callerId, "PRIVILEGED_ACTION_REQUESTED",
                "Requested '" + permission + "' on account #USR-" + targetUserId + ". Reason: " + reason.trim(),
                http);
        return created;
    }

    /** Approves or denies a pending request. The approver may never be the requester. */
    @Transactional
    public PrivilegedActionRequest decide(Long requestId, boolean approve, String note,
                                          HttpServletRequest http) {
        Long approverId = requireCaller();
        PrivilegedActionRequest row = requests.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("No such request."));

        if (!PrivilegedActionRequest.PENDING.equals(row.getStatus())) {
            throw new IllegalArgumentException("This request is already " + row.getStatus().toLowerCase() + ".");
        }
        if (row.getExpiresAt().isBefore(LocalDateTime.now())) {
            requests.expire(requestId, "Expired before a decision was made.");
            throw new IllegalArgumentException("This request has expired.");
        }
        if (approverId.equals(row.getRequestedBy())) {
            throw new IllegalArgumentException("A request cannot be decided by the account that made it.");
        }
        if (!authz.permissionsFor(approverId).contains(APPROVER_PERMISSION)) {
            throw new IllegalArgumentException("You may not approve privileged actions.");
        }
        if (!isPeerOrSuperior(approverId, row.getRequestedBy())) {
            throw new IllegalArgumentException("Only an administrator of the requester's rank or above may decide this.");
        }

        int changed = requests.decide(requestId, approve
                ? PrivilegedActionRequest.APPROVED
                : PrivilegedActionRequest.DENIED, approverId, note);
        if (changed != 1) {
            throw new IllegalStateException("Someone else decided this request first.");
        }

        audit(approverId, approve ? "PRIVILEGED_ACTION_APPROVED" : "PRIVILEGED_ACTION_DENIED",
                (approve ? "Approved" : "Denied") + " '" + row.getPermissionKey() + "' on account #USR-"
                        + row.getTargetUserId() + " requested by #USR-" + row.getRequestedBy()
                        + (note == null || note.isBlank() ? "." : ". Note: " + note.trim()),
                http);

        return requests.findById(requestId).orElseThrow();
    }

    /**
     * Consumes an approval, allowing the action it names. Called from the authorization
     * check of the action itself, so the approval and the action cannot drift apart.
     *
     * @return true only when this call is the one that moved the request to EXECUTED
     */
    @Transactional
    public boolean redeem(Long requestId, Long callerId, String permission, Long targetUserId) {
        if (requestId == null || callerId == null) {
            return false;
        }
        PrivilegedActionRequest row = requests.findById(requestId).orElse(null);
        if (row == null
                || !PrivilegedActionRequest.APPROVED.equals(row.getStatus())
                || !row.getRequestedBy().equals(callerId)
                || !row.getPermissionKey().equals(permission)
                || targetUserId == null
                || !row.getTargetUserId().equals(targetUserId)
                || row.getExpiresAt().isBefore(LocalDateTime.now())) {
            return false;
        }
        boolean redeemed = requests.markExecuted(requestId) == 1;
        if (!redeemed) {
            log.warn("approval {} could not be redeemed: already used by a concurrent request", requestId);
        }
        return redeemed;
    }

    /** Requests waiting for a decision, plus the caller's own open requests. */
    @Transactional(readOnly = true)
    public List<PrivilegedActionRequest> overview(Long callerId, boolean mineOnly) {
        if (mineOnly) {
            return requests.findByRequestedByOrderByRequestedAtDesc(callerId);
        }
        List<PrivilegedActionRequest> pending = requests.findByStatusOrderByRequestedAtDesc(
                PrivilegedActionRequest.PENDING);
        List<PrivilegedActionRequest> own = requests.findByRequestedByOrderByRequestedAtDesc(callerId);
        return java.util.stream.Stream.concat(pending.stream(), own.stream())
                .distinct()
                .toList();
    }

    private boolean isPeerOrSuperior(Long approverId, Long requesterId) {
        Integer approverRank = authz.rankOf(approverId);
        Integer requesterRank = authz.rankOf(requesterId);
        // Unknown roles cannot be ordered against anybody, so they cannot approve.
        return approverRank != null && requesterRank != null && approverRank >= requesterRank;
    }

    private PrivilegedActionRequest openRequest(String permission, Long targetUserId, Long callerId,
                                                String status) {
        List<PrivilegedActionRequest> found = requests
                .findByPermissionKeyAndTargetUserIdAndRequestedByAndStatus(permission, targetUserId, callerId, status);
        return found.isEmpty() ? null : found.get(0);
    }

    private Long requireCaller() {
        Long callerId = SecurityUtils.getCurrentUserId();
        if (callerId == null) {
            throw new IllegalStateException("An authenticated administrator is required.");
        }
        return callerId;
    }

    private void audit(Long actorId, String action, String description, HttpServletRequest http) {
        User actor = users.findById(actorId).orElse(null);
        activityLog.log(actor, "USER", action, "#USR-" + actorId, description, "WARNING", http);
    }
}

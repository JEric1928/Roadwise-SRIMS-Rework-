package com.roadwise.backend.service;

import com.roadwise.backend.repository.AuthzDecisionRepository;
import com.roadwise.backend.repository.AuthzRepository;
import com.roadwise.backend.util.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.UUID;

/**
 * The single place authorization is decided for administrative actions.
 *
 * <p>Called from {@code @PreAuthorize} expressions as the bean {@code authz}, so a rule
 * reads {@code @authz.canActOn(#id, 'user:reset-password')} at the endpoint it protects.
 * Keeping the decision here rather than in each annotation matters: {@code hasAnyRole}
 * could express "may do this" but never "may do this *to that account*", which is the
 * question every escalation endpoint actually asks.
 *
 * <p>Resolution order for acting on another account:
 *
 * <ol>
 *   <li>the caller must hold the permission (an unknown role holds none -- fail closed);</li>
 *   <li>acting on yourself is allowed once the permission is held;</li>
 *   <li>a strictly higher rank is allowed outright;</li>
 *   <li>a live break-glass grant substitutes for rank, for minutes at a time, and is loud;</li>
 *   <li>an approved maker-checker ticket substitutes for rank, once, bound to this action.</li>
 * </ol>
 *
 * <p>Every outcome is recorded in {@code authz_decision_log} -- the refusals especially,
 * because a refused attempt is the event an incident review wants to see and the one the
 * previous implementation left no trace of. Allowed routine reads are not recorded; that
 * would put a write behind every authenticated request.
 */
@Component("authz")
public class AuthorizationService {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationService.class);

    /** Why a decision came out the way it did. Stored verbatim in the audit trail. */
    static final String NO_PERMISSION = "NO_PERMISSION";
    static final String SELF = "SELF";
    static final String RANK = "RANK";
    static final String UNKNOWN_RANK = "UNKNOWN_RANK";
    static final String UNKNOWN_TARGET = "UNKNOWN_TARGET";
    static final String PEER_OR_SUPERIOR = "PEER_OR_SUPERIOR";
    static final String APPROVED = "APPROVED";
    static final String BREAK_GLASS = "BREAK_GLASS";

    private final AuthzRepository authzRepository;
    private final ApprovalService approvals;
    private final BreakGlassService breakGlass;
    private final AuthzDecisionRepository decisions;

    public AuthorizationService(AuthzRepository authzRepository,
                                ApprovalService approvals,
                                BreakGlassService breakGlass,
                                AuthzDecisionRepository decisions) {
        this.authzRepository = authzRepository;
        this.approvals = approvals;
        this.breakGlass = breakGlass;
        this.decisions = decisions;
    }

    /** Whether the authenticated caller carries this permission. */
    public boolean has(String permission) {
        return has(SecurityUtils.getCurrentUserId(), permission);
    }

    /** Whether a specific account carries this permission. A refusal is recorded. */
    public boolean has(Long userId, String permission) {
        if (permissionGranted(userId, permission)) {
            return true;
        }
        record(userId, permission, null, "DENY", NO_PERMISSION);
        return false;
    }

    /**
     * Whether the caller may perform an action on the given account.
     *
     * <p>Self-service is allowed for any permission the caller holds: acting on your own
     * account is not an escalation. Acting on somebody else requires a strictly higher rank,
     * a live break-glass grant, or an approved ticket.
     */
    public boolean canActOn(Long targetUserId, String permission) {
        return canActOn(targetUserId, permission, null);
    }

    /**
     * As above, but accepts a maker-checker approval in place of rank.
     *
     * @param approvalId the {@code X-Approval-Id} header, or {@code null} when none was sent
     */
    public boolean canActOn(Long targetUserId, String permission, Long approvalId) {
        Long callerId = SecurityUtils.getCurrentUserId();
        boolean allowed;
        String reason;

        if (!permissionGranted(callerId, permission)) {
            allowed = false;
            reason = NO_PERMISSION;
        } else if (callerId.equals(targetUserId)) {
            allowed = true;
            reason = SELF;
        } else if (authzRepository.rankOf(callerId) == null) {
            // A caller whose role nobody aliased cannot be ordered against anybody.
            log.warn("authorization denied: caller {} holds '{}' but has no rank in authz_role", callerId, permission);
            allowed = false;
            reason = UNKNOWN_RANK;
        } else {
            int callerRank = authzRepository.rankOf(callerId);
            Integer targetRank = rankOf(targetUserId);
            if (targetRank == null) {
                // An account without an aliased role ranks below every known role, and cannot
                // itself hold a permission, so acting on it grants no escalation.
                allowed = true;
                reason = UNKNOWN_TARGET;
            } else if (callerRank > targetRank) {
                allowed = true;
                reason = RANK;
            } else if (substituteForRank(callerId)) {
                allowed = true;
                reason = BREAK_GLASS;
            } else if (redeemApproval(approvalId, callerId, permission, targetUserId)) {
                allowed = true;
                reason = APPROVED;
            } else {
                allowed = false;
                reason = PEER_OR_SUPERIOR;
            }
        }

        record(callerId, permission, targetUserId, allowed ? "ALLOW" : "DENY", reason);
        return allowed;
    }

    private boolean substituteForRank(Long callerId) {
        try {
            return breakGlass.isActive(callerId);
        } catch (RuntimeException ex) {
            log.warn("break-glass check failed, treating as absent: {}", ex.toString());
            return false;
        }
    }

    private boolean redeemApproval(Long approvalId, Long callerId, String permission, Long targetUserId) {
        if (approvalId == null) {
            return false;
        }
        try {
            return approvals.redeem(approvalId, callerId, permission, targetUserId);
        } catch (RuntimeException ex) {
            // A failed redemption is a refused action, never an allowed one.
            log.warn("approval {} could not be redeemed for '{}' on account {}: {}",
                    approvalId, permission, targetUserId, ex.toString());
            return false;
        }
    }

    private boolean permissionGranted(Long userId, String permission) {
        return userId != null
                && permission != null
                && !permission.isBlank()
                && authzRepository.permissionsFor(userId).contains(permission);
    }

    private Integer rankOf(Long userId) {
        return userId == null ? null : authzRepository.rankOf(userId);
    }

    /**
     * Persists one decision row. Never throws: an audit write must not be the reason a
     * legitimate request fails.
     */
    private void record(Long actorId, String permission, Long targetUserId, String decision, String reason) {
        HttpServletRequest request = currentRequest();
        String correlationId = correlationId(request);
        decisions.record(actorId, permission, targetUserId, decision, reason,
                request == null ? null : request.getMethod(),
                request == null ? null : truncate(request.getRequestURI(), 255),
                correlationId);
    }

    private static HttpServletRequest currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        return attributes instanceof ServletRequestAttributes servlet ? servlet.getRequest() : null;
    }

    /** Reuses a caller-supplied request id so one action's decisions can be grouped. */
    private static String correlationId(HttpServletRequest request) {
        String header = request == null ? null : request.getHeader("X-Request-Id");
        return header == null || header.isBlank() ? UUID.randomUUID().toString() : truncate(header, 64);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}

package com.roadwise.backend.service;

import com.roadwise.backend.model.BreakGlassGrant;
import com.roadwise.backend.model.User;
import com.roadwise.backend.repository.AuthzRepository;
import com.roadwise.backend.repository.BreakGlassGrantRepository;
import com.roadwise.backend.repository.UserRepository;
import com.roadwise.backend.util.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The controlled exception to "you may not act on a peer".
 *
 * <p>Maker-checker covers the everyday case. It cannot cover the last-administrator case,
 * where the person who needs the action is the only person able to approve it. The realistic
 * alternatives there are worse than this: an outage, or somebody with the database password
 * editing rows by hand.
 *
 * <p>So the grant is self-issued, and every property that makes that acceptable is a
 * constraint rather than a promise: it expires within the hour, the holder must already hold
 * the permission they want to use (the grant unlocks rank, never capability), a written
 * reason is required, every other administrator is notified when it happens, and the row
 * stays behind with a review field that somebody has to fill in.
 *
 * <p>What it deliberately does not do: gate activation on paperwork. An emergency path that
 * waits for review is one people route around, and the rows would be missing rather than late.
 */
@Service
public class BreakGlassService {

    private static final Logger log = LoggerFactory.getLogger(BreakGlassService.class);

    public static final String PERMISSION = "user:break-glass";
    public static final int MIN_MINUTES = 5;
    public static final int MAX_MINUTES = 60;
    public static final int DEFAULT_MINUTES = 30;
    public static final int MIN_REASON_LENGTH = 10;

    private final BreakGlassGrantRepository grants;
    private final AuthzRepository authz;
    private final UserRepository users;
    private final ActivityLogService activityLog;
    private final NotificationService notifications;

    public BreakGlassService(BreakGlassGrantRepository grants,
                             AuthzRepository authz,
                             UserRepository users,
                             ActivityLogService activityLog,
                             NotificationService notifications) {
        this.grants = grants;
        this.authz = authz;
        this.users = users;
        this.activityLog = activityLog;
        this.notifications = notifications;
    }

    /** Whether this account currently holds a live grant. Called from the authorization path. */
    @Transactional(readOnly = true)
    public boolean isActive(Long userId) {
        return userId != null && liveGrant(userId) != null;
    }

    @Transactional
    public BreakGlassGrant activate(String reason, Integer minutes, HttpServletRequest http) {
        Long callerId = requireCaller();
        if (!authz.permissionsFor(callerId).contains(PERMISSION)) {
            throw new IllegalArgumentException("You may not use break-glass access.");
        }
        int duration = minutes == null ? DEFAULT_MINUTES : minutes;
        if (duration < MIN_MINUTES || duration > MAX_MINUTES) {
            throw new IllegalArgumentException(
                    "Break-glass lasts between " + MIN_MINUTES + " and " + MAX_MINUTES + " minutes.");
        }
        if (reason == null || reason.trim().length() < MIN_REASON_LENGTH) {
            throw new IllegalArgumentException("Explain why this is needed (at least "
                    + MIN_REASON_LENGTH + " characters); the grant is reviewed afterwards.");
        }
        if (liveGrant(callerId) != null) {
            throw new IllegalArgumentException("You already hold a live break-glass grant.");
        }

        BreakGlassGrant grant = grants.save(new BreakGlassGrant(
                callerId, reason.trim(), LocalDateTime.now().plusMinutes(duration)));

        alertOtherAdministrators(callerId, duration, reason.trim());
        audit(callerId, "BREAK_GLASS_ACTIVATED",
                "Break-glass access for " + duration + " minutes. Reason: " + reason.trim(), http);
        log.warn("break-glass granted to user {} for {} minutes: {}", callerId, duration, reason.trim());
        return grant;
    }

    @Transactional
    public BreakGlassGrant revoke(Long grantId, HttpServletRequest http) {
        Long callerId = requireCaller();
        BreakGlassGrant grant = grants.findById(grantId)
                .orElseThrow(() -> new IllegalArgumentException("No such grant."));
        boolean own = grant.getGrantedTo().equals(callerId);
        if (!own && !authz.permissionsFor(callerId).contains(ApprovalService.APPROVER_PERMISSION)) {
            throw new IllegalArgumentException("You may not revoke this grant.");
        }
        if (grant.getRevokedAt() == null) {
            grant.setRevokedAt(LocalDateTime.now());
            grants.save(grant);
            audit(callerId, "BREAK_GLASS_REVOKED", "Revoked break-glass grant #" + grantId + ".", http);
        }
        return grant;
    }

    @Transactional
    public BreakGlassGrant review(Long grantId, String note, HttpServletRequest http) {
        Long callerId = requireCaller();
        if (!authz.permissionsFor(callerId).contains(ApprovalService.APPROVER_PERMISSION)) {
            throw new IllegalArgumentException("You may not review break-glass access.");
        }
        BreakGlassGrant grant = grants.findById(grantId)
                .orElseThrow(() -> new IllegalArgumentException("No such grant."));
        if (grant.getReviewedAt() == null) {
            grant.setReviewedBy(callerId);
            grant.setReviewedAt(LocalDateTime.now());
            grant.setReviewNote(note == null ? null : note.trim());
            grants.save(grant);
            audit(callerId, "BREAK_GLASS_REVIEWED", "Reviewed break-glass grant #" + grantId
                    + (note == null || note.isBlank() ? "." : ". Note: " + note.trim()), http);
        }
        return grant;
    }

    @Transactional(readOnly = true)
    public List<BreakGlassGrant> overview() {
        return grants.findTop50ByOrderByGrantedAtDesc();
    }

    private BreakGlassGrant liveGrant(Long userId) {
        return grants
                .findFirstByGrantedToAndRevokedAtIsNullAndExpiresAtAfterOrderByExpiresAtDesc(
                        userId, LocalDateTime.now())
                .orElse(null);
    }

    /**
     * Tells the other administrators, not the caller. The alert is the point of a self-issued
     * grant: somebody else finds out while it is happening, not at the next audit.
     */
    private void alertOtherAdministrators(Long callerId, int minutes, String reason) {
        try {
            List<User> admins = users.findByRoles(java.util.List.of("admin", "cpdo admin"));
            for (User admin : admins) {
                if (admin.getId() == null || admin.getId().equals(callerId)) {
                    continue;
                }
                notifications.sendNotification(admin.getId(),
                        "Break-glass access activated",
                        "An administrator granted themselves peer access for " + minutes
                                + " minutes. Reason: " + reason,
                        "SECURITY");
            }
        } catch (RuntimeException ex) {
            // A failed notification must not deny the emergency path the grant was opened for;
            // the audit row and the warning log still record that it happened.
            log.warn("could not alert administrators about a break-glass grant: {}", ex.toString());
        }
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
        activityLog.log(actor, "AUTH", action, "#USR-" + actorId, description, "WARNING", http);
    }
}

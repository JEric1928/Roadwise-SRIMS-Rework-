package com.roadwise.backend.service;

import com.roadwise.backend.model.ReportStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class ReportWorkflowService {

    // Targets only administrators may request through the generic status
    // endpoint: review decisions, dispatch, un-archiving and archiving are
    // administrative actions. Engineers own the repair states only.
    private static final Set<ReportStatus> ADMIN_ONLY_TARGETS = Set.of(
            ReportStatus.VALIDATED,
            ReportStatus.REJECTED,
            ReportStatus.DISPATCHED_TO_CEO,
            ReportStatus.PENDING_VALIDATION,
            ReportStatus.ARCHIVED);

    private final Map<ReportStatus, Set<ReportStatus>> allowedTransitions = new EnumMap<>(ReportStatus.class);

    public ReportWorkflowService() {
        allowedTransitions.put(ReportStatus.PENDING_VALIDATION, Set.of(
                ReportStatus.VALIDATED,
                ReportStatus.REJECTED,
                ReportStatus.ARCHIVED
        ));

        allowedTransitions.put(ReportStatus.REJECTED, Set.of(
                ReportStatus.RESUBMITTED,
                ReportStatus.PENDING_VALIDATION,
                ReportStatus.ARCHIVED
        ));

        allowedTransitions.put(ReportStatus.RESUBMITTED, Set.of(
                ReportStatus.VALIDATED,
                ReportStatus.REJECTED,
                ReportStatus.ARCHIVED
        ));

        allowedTransitions.put(ReportStatus.VALIDATED, Set.of(
                ReportStatus.DISPATCHED_TO_CEO,
                ReportStatus.PENDING_BUDGET,
                ReportStatus.REJECTED,
                ReportStatus.ARCHIVED
        ));

        allowedTransitions.put(ReportStatus.PENDING_BUDGET, Set.of(
                ReportStatus.VALIDATED,
                ReportStatus.DISPATCHED_TO_CEO,
                ReportStatus.REJECTED,
                ReportStatus.ARCHIVED
        ));

        allowedTransitions.put(ReportStatus.DISPATCHED_TO_CEO, Set.of(
                ReportStatus.IN_PROGRESS,
                ReportStatus.PENDING_BUDGET,
                ReportStatus.COMPLETED,
                ReportStatus.ARCHIVED
        ));

        allowedTransitions.put(ReportStatus.IN_PROGRESS, Set.of(
                ReportStatus.COMPLETED,
                ReportStatus.RESOLVED,
                ReportStatus.PENDING_BUDGET,
                ReportStatus.ARCHIVED
        ));

        allowedTransitions.put(ReportStatus.COMPLETED, Set.of(
                ReportStatus.RESOLVED,
                ReportStatus.CLOSED,
                ReportStatus.ARCHIVED
        ));

        allowedTransitions.put(ReportStatus.RESOLVED, Set.of(
                ReportStatus.CLOSED,
                ReportStatus.ARCHIVED
        ));

        allowedTransitions.put(ReportStatus.CLOSED, Set.of(
                ReportStatus.ARCHIVED
        ));

        allowedTransitions.put(ReportStatus.ARCHIVED, Set.of(
                ReportStatus.PENDING_VALIDATION
        ));
    }

    public boolean isValidTransition(ReportStatus currentStatus, ReportStatus targetStatus) {
        if (currentStatus == targetStatus) {
            return true;
        }
        Set<ReportStatus> validTargets = allowedTransitions.get(currentStatus);
        return validTargets != null && validTargets.contains(targetStatus);
    }

    public void validateTransition(ReportStatus currentStatus, ReportStatus targetStatus) {
        if (!isValidTransition(currentStatus, targetStatus)) {
            throw new IllegalArgumentException(
                    String.format("Invalid report status transition from '%s' to '%s'.",
                            currentStatus.getLabel(), targetStatus.getLabel())
            );
        }
    }

    /**
     * State-machine check plus the role matrix: administrative targets
     * (validate/reject/dispatch/archive/un-archive) are refused for engineer
     * callers, so the generic endpoint cannot bypass dedicated admin flows.
     *
     * @param callerRoles authorities of the caller, with or without the
     *                    {@code ROLE_} prefix.
     */
    public void validateTransitionForRole(ReportStatus currentStatus, ReportStatus targetStatus,
                                          Collection<String> callerRoles) {
        validateTransition(currentStatus, targetStatus);
        if (ADMIN_ONLY_TARGETS.contains(targetStatus) && !hasAdminRole(callerRoles)) {
            throw new AccessDeniedException(
                    String.format("Transition to '%s' requires administrator permission.",
                            targetStatus.getLabel())
            );
        }
    }

    private boolean hasAdminRole(Collection<String> callerRoles) {
        if (callerRoles == null) {
            return false;
        }
        for (String role : callerRoles) {
            if (role == null) {
                continue;
            }
            String normalized = role.replaceFirst("^ROLE_", "");
            if (normalized.equalsIgnoreCase("ADMIN") || normalized.equalsIgnoreCase("CPDO Admin")) {
                return true;
            }
        }
        return false;
    }
}

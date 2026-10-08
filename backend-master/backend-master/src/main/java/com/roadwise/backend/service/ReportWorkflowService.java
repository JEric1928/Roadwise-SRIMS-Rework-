package com.roadwise.backend.service;

import com.roadwise.backend.model.ReportStatus;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class ReportWorkflowService {

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
                ReportStatus.COMPLETED,
                ReportStatus.ARCHIVED
        ));

        allowedTransitions.put(ReportStatus.IN_PROGRESS, Set.of(
                ReportStatus.COMPLETED,
                ReportStatus.RESOLVED,
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
}

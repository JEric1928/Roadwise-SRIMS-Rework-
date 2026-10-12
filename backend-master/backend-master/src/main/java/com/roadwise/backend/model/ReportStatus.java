package com.roadwise.backend.model;

public enum ReportStatus {
    PENDING_VALIDATION("Pending Validation"),
    VALIDATED("Validated"),
    REJECTED("Rejected"),
    RESUBMITTED("Resubmitted"),
    DISPATCHED_TO_CEO("Dispatched to CEO"),
    IN_PROGRESS("In Progress"),
    PENDING_BUDGET("Pending Budget"),
    COMPLETED("Completed"),
    CLOSED("Closed"),
    RESOLVED("Resolved"),
    ARCHIVED("Archived");

    private final String label;

    ReportStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public static ReportStatus fromString(String statusStr) {
        if (statusStr == null || statusStr.trim().isEmpty()) {
            throw new IllegalArgumentException("Report status cannot be null or empty.");
        }
        for (ReportStatus rs : ReportStatus.values()) {
            if (rs.name().equalsIgnoreCase(statusStr) || rs.getLabel().equalsIgnoreCase(statusStr)) {
                return rs;
            }
        }
        throw new IllegalArgumentException("Unknown or invalid report status: " + statusStr);
    }
}

package com.roadwise.backend.service;

import com.roadwise.backend.model.ReportStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReportWorkflowServiceTest {

    private ReportWorkflowService workflowService;

    @BeforeEach
    void setUp() {
        workflowService = new ReportWorkflowService();
    }

    @Test
    void testValidTransitions() {
        assertTrue(workflowService.isValidTransition(ReportStatus.PENDING_VALIDATION, ReportStatus.VALIDATED));
        assertTrue(workflowService.isValidTransition(ReportStatus.PENDING_VALIDATION, ReportStatus.REJECTED));
        assertTrue(workflowService.isValidTransition(ReportStatus.VALIDATED, ReportStatus.DISPATCHED_TO_CEO));
        assertTrue(workflowService.isValidTransition(ReportStatus.DISPATCHED_TO_CEO, ReportStatus.IN_PROGRESS));
        assertTrue(workflowService.isValidTransition(ReportStatus.IN_PROGRESS, ReportStatus.COMPLETED));
        assertTrue(workflowService.isValidTransition(ReportStatus.COMPLETED, ReportStatus.RESOLVED));
    }

    @Test
    void testInvalidTransitions() {
        assertFalse(workflowService.isValidTransition(ReportStatus.PENDING_VALIDATION, ReportStatus.COMPLETED));
        assertFalse(workflowService.isValidTransition(ReportStatus.REJECTED, ReportStatus.COMPLETED));
        assertFalse(workflowService.isValidTransition(ReportStatus.VALIDATED, ReportStatus.CLOSED));
    }

    @Test
    void testValidateTransitionThrowsException() {
        assertThrows(IllegalArgumentException.class, () ->
                workflowService.validateTransition(ReportStatus.PENDING_VALIDATION, ReportStatus.COMPLETED)
        );
    }
}

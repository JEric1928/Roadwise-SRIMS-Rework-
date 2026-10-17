package com.roadwise.backend.service;

import com.roadwise.backend.model.PrivilegedActionRequest;
import com.roadwise.backend.model.User;
import com.roadwise.backend.repository.AuthzRepository;
import com.roadwise.backend.repository.PrivilegedActionRequestRepository;
import com.roadwise.backend.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The maker-checker rules that keep a ticket from becoming a skeleton key.
 *
 * <p>Every case here is a way the control would quietly stop being a control: approving your
 * own request, a junior approving a senior, a ticket naming a different account than the one
 * being touched, or the same approval authorizing a second action.
 */
class ApprovalServiceTest {

    private static final long MAKER = 1L;
    private static final long CHECKER = 2L;
    private static final long JUNIOR = 3L;
    private static final long TARGET = 7L;
    private static final String PERMISSION = "user:reset-password";

    private final PrivilegedActionRequestRepository requests = mock(PrivilegedActionRequestRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final AuthzRepository authz = mock(AuthzRepository.class);
    private final ActivityLogService activityLog = mock(ActivityLogService.class);

    private final ApprovalService approvals =
            new ApprovalService(requests, users, authz, activityLog);

    @AfterEach
    void clearPrincipal() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- requesting

    @Test
    void yourOwnAccountDoesNotNeedAnApproval() {
        authenticate(MAKER);
        when(authz.permissionsFor(MAKER)).thenReturn(List.of(PERMISSION));
        when(users.findById(MAKER)).thenReturn(Optional.of(user(MAKER)));

        assertThrows(IllegalArgumentException.class,
                () -> approvals.request(PERMISSION, MAKER, "because", null));
    }

    @Test
    void aMakerCannotRequestAPermissionTheyDoNotHold() {
        authenticate(MAKER);
        when(authz.permissionsFor(MAKER)).thenReturn(List.of("user:read"));

        assertThrows(IllegalArgumentException.class,
                () -> approvals.request(PERMISSION, TARGET, "because", null));
    }

    @Test
    void aReasonIsRequired() {
        authenticate(MAKER);
        when(authz.permissionsFor(MAKER)).thenReturn(List.of(PERMISSION));
        when(users.findById(TARGET)).thenReturn(Optional.of(user(TARGET)));

        assertThrows(IllegalArgumentException.class,
                () -> approvals.request(PERMISSION, TARGET, "   ", null));
    }

    @Test
    void anOpenRequestIsReusedRatherThanDuplicated() {
        authenticate(MAKER);
        when(authz.permissionsFor(MAKER)).thenReturn(List.of(PERMISSION));
        when(users.findById(TARGET)).thenReturn(Optional.of(user(TARGET)));
        PrivilegedActionRequest open = row(PrivilegedActionRequest.PENDING, MAKER, TARGET, future());
        when(requests.findByPermissionKeyAndTargetUserIdAndRequestedByAndStatus(
                PERMISSION, TARGET, MAKER, PrivilegedActionRequest.PENDING)).thenReturn(List.of(open));

        PrivilegedActionRequest returned = approvals.request(PERMISSION, TARGET, "because", null);

        assertTrue(returned == open);
        verify(requests, never()).save(any());
    }

    // ---------------------------------------------------------------- deciding

    @Test
    void aRequestCannotBeDecidedByItsOwnMaker() {
        authenticate(MAKER);
        givenPendingRequest();

        assertThrows(IllegalArgumentException.class, () -> approvals.decide(5L, true, null, null));
    }

    @Test
    void aJuniorAdministratorCannotApprove() {
        authenticate(JUNIOR);
        givenPendingRequest();
        when(authz.permissionsFor(JUNIOR)).thenReturn(List.of(ApprovalService.APPROVER_PERMISSION));
        when(authz.rankOf(JUNIOR)).thenReturn(20);
        when(authz.rankOf(MAKER)).thenReturn(50);

        assertThrows(IllegalArgumentException.class, () -> approvals.decide(5L, true, null, null));
    }

    @Test
    void anExpiredRequestCannotBeDecided() {
        authenticate(CHECKER);
        PrivilegedActionRequest expired = row(PrivilegedActionRequest.PENDING, MAKER, TARGET, LocalDateTime.now().minusHours(1));
        when(requests.findById(5L)).thenReturn(Optional.of(expired));

        assertThrows(IllegalArgumentException.class, () -> approvals.decide(5L, true, null, null));
    }

    @Test
    void aPeerApprovesARequest() {
        authenticate(CHECKER);
        givenPendingRequest();
        when(authz.permissionsFor(CHECKER)).thenReturn(List.of(ApprovalService.APPROVER_PERMISSION));
        when(authz.rankOf(CHECKER)).thenReturn(50);
        when(authz.rankOf(MAKER)).thenReturn(50);
        when(requests.decide(5L, PrivilegedActionRequest.APPROVED, CHECKER, "agreed")).thenReturn(1);

        approvals.decide(5L, true, "agreed", null);

        verify(requests).decide(5L, PrivilegedActionRequest.APPROVED, CHECKER, "agreed");
    }

    // ---------------------------------------------------------------- redeeming

    @Test
    void anApprovedTicketIsRedeemableOnce() {
        when(requests.findById(5L)).thenReturn(Optional.of(
                row(PrivilegedActionRequest.APPROVED, MAKER, TARGET, future())));
        when(requests.markExecuted(5L)).thenReturn(1, 0);

        assertTrue(approvals.redeem(5L, MAKER, PERMISSION, TARGET));
        assertFalse(approvals.redeem(5L, MAKER, PERMISSION, TARGET), "the second use must find nothing to execute");
    }

    @Test
    void aTicketOnlyWorksForTheAccountThatAskedForIt() {
        when(requests.findById(5L)).thenReturn(Optional.of(
                row(PrivilegedActionRequest.APPROVED, MAKER, TARGET, future())));

        assertFalse(approvals.redeem(5L, CHECKER, PERMISSION, TARGET));
    }

    @Test
    void aTicketOnlyWorksForTheAccountItNames() {
        when(requests.findById(5L)).thenReturn(Optional.of(
                row(PrivilegedActionRequest.APPROVED, MAKER, TARGET, future())));

        assertFalse(approvals.redeem(5L, MAKER, PERMISSION, 99L));
    }

    @Test
    void aTicketOnlyWorksForThePermissionItWasApprovedFor() {
        when(requests.findById(5L)).thenReturn(Optional.of(
                row(PrivilegedActionRequest.APPROVED, MAKER, TARGET, future())));

        assertFalse(approvals.redeem(5L, MAKER, "user:manage", TARGET));
    }

    @Test
    void anExpiredApprovalCannotBeRedeemed() {
        when(requests.findById(5L)).thenReturn(Optional.of(
                row(PrivilegedActionRequest.APPROVED, MAKER, TARGET, LocalDateTime.now().minusMinutes(1))));

        assertFalse(approvals.redeem(5L, MAKER, PERMISSION, TARGET));
        verify(requests, never()).markExecuted(anyLong());
    }

    @Test
    void aPendingTicketCannotBeRedeemed() {
        when(requests.findById(5L)).thenReturn(Optional.of(
                row(PrivilegedActionRequest.PENDING, MAKER, TARGET, future())));

        assertFalse(approvals.redeem(5L, MAKER, PERMISSION, TARGET));
        verify(requests, never()).markExecuted(anyLong());
    }

    @Test
    void anUnknownTicketIsRefused() {
        when(requests.findById(anyLong())).thenReturn(Optional.empty());

        assertFalse(approvals.redeem(404L, MAKER, PERMISSION, TARGET));
        verify(requests, never()).markExecuted(anyLong());
    }

    // ---------------------------------------------------------------- helpers

    private void givenPendingRequest() {
        when(requests.findById(5L)).thenReturn(Optional.of(
                row(PrivilegedActionRequest.PENDING, MAKER, TARGET, future())));
        when(users.findById(anyLong())).thenReturn(Optional.of(user(MAKER)));
    }

    private static PrivilegedActionRequest row(String status, long requestedBy, long target, LocalDateTime expiresAt) {
        PrivilegedActionRequest row = new PrivilegedActionRequest(PERMISSION, target, requestedBy, "because", expiresAt);
        row.setId(5L);
        row.setStatus(status);
        return row;
    }

    private static LocalDateTime future() {
        return LocalDateTime.now().plusHours(1);
    }

    private static User user(long id) {
        User user = new User();
        user.setId(id);
        return user;
    }

    private void authenticate(long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user(userId), null, List.of()));
    }
}

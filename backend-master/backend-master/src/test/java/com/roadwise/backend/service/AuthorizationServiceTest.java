package com.roadwise.backend.service;

import com.roadwise.backend.model.User;
import com.roadwise.backend.repository.AuthzDecisionRepository;
import com.roadwise.backend.repository.AuthzRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The two rules that keep an administrative session from becoming another account.
 *
 * <p>Both failure modes are silent in production: a permission that resolves for the wrong
 * caller looks like a working feature, and a rank comparison that admits equals looks like
 * an administrator doing their job -- right up until one administrator resets another's
 * password and logs in as them.
 */
class AuthorizationServiceTest {

    private static final long ADMIN = 2L;
    private static final long PEER_ADMIN = 3L;
    private static final long ENGINEER = 9L;
    private static final long OFFICIAL = 17L;

    private final AuthzRepository repository = mock(AuthzRepository.class);
    private final ApprovalService approvals = mock(ApprovalService.class);
    private final BreakGlassService breakGlass = mock(BreakGlassService.class);
    private final AuthzDecisionRepository decisions = mock(AuthzDecisionRepository.class);
    private final AuthorizationService authz =
            new AuthorizationService(repository, approvals, breakGlass, decisions);

    @AfterEach
    void clearPrincipal() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void anUnauthenticatedCallerHoldsNothing() {
        assertFalse(authz.has("user:read"));
        assertFalse(authz.canActOn(OFFICIAL, "user:read"));
    }

    @Test
    void permissionsComeFromTheCallersRole() {
        authenticate(ADMIN);
        when(repository.permissionsFor(ADMIN)).thenReturn(List.of("user:read", "user:manage"));

        assertTrue(authz.has("user:read"));
        assertTrue(authz.has("user:manage"));
        assertFalse(authz.has("settings:maintenance"));
    }

    /** An unknown role resolves to no rows, and must therefore carry no permission. */
    @Test
    void aRoleWithNoPolicyRowsCarriesNoPermission() {
        authenticate(ENGINEER);

        assertFalse(authz.has("user:read"));
    }

    @Test
    void actingOnYourOwnAccountNeedsThePermissionButNoRankComparison() {
        authenticate(ADMIN);
        when(repository.permissionsFor(ADMIN)).thenReturn(List.of("user:reset-password"));

        assertTrue(authz.canActOn(ADMIN, "user:reset-password"));
        assertFalse(authz.canActOn(ADMIN, "settings:maintenance"));
    }

    @Test
    void anAdministratorMayActOnALowerRankedAccount() {
        authenticate(ADMIN);
        givenRanks(ADMIN, 50, OFFICIAL, 10);

        assertTrue(authz.canActOn(OFFICIAL, "user:reset-password"));
    }

    /** The whole point: two administrators at the same rank cannot take each other over. */
    @Test
    void anAdministratorMayNotActOnAPeer() {
        authenticate(ADMIN);
        givenRanks(ADMIN, 50, PEER_ADMIN, 50);

        assertFalse(authz.canActOn(PEER_ADMIN, "user:reset-password"));
        assertFalse(authz.canActOn(PEER_ADMIN, "user:manage"));
    }

    @Test
    void aLowerRankedCallerMayNotActOnAHigherRankedAccount() {
        authenticate(ENGINEER);
        givenRanks(ENGINEER, 20, ADMIN, 50);

        assertFalse(authz.canActOn(ADMIN, "user:manage"));
    }

    /** A caller whose own role has no rank cannot order itself against anybody. */
    @Test
    void aCallerWithoutARankIsDeniedEvenWithThePermission() {
        authenticate(ENGINEER);
        when(repository.permissionsFor(ENGINEER)).thenReturn(List.of("user:manage"));
        when(repository.rankOf(anyLong())).thenReturn(null);

        assertFalse(authz.canActOn(OFFICIAL, "user:manage"));
    }

    /** An account whose role nobody aliased ranks below every known role. */
    @Test
    void anAccountWithNoRankIsActedOn() {
        authenticate(ADMIN);
        when(repository.permissionsFor(ADMIN)).thenReturn(List.of("user:manage"));
        when(repository.rankOf(ADMIN)).thenReturn(50);
        when(repository.rankOf(OFFICIAL)).thenReturn(null);

        assertTrue(authz.canActOn(OFFICIAL, "user:manage"));
    }

    // ---------------------------------------------------------------- maker-checker

    /** The peer case: rank refuses, and no ticket was offered. */
    @Test
    void aPeerActionWithoutATicketIsRefused() {
        authenticate(ADMIN);
        givenRanks(ADMIN, 50, PEER_ADMIN, 50);

        assertFalse(authz.canActOn(PEER_ADMIN, "user:reset-password", null));
    }

    @Test
    void anApprovedTicketAllowsThePeerAction() {
        authenticate(ADMIN);
        givenRanks(ADMIN, 50, PEER_ADMIN, 50);
        when(approvals.redeem(99L, ADMIN, "user:reset-password", PEER_ADMIN)).thenReturn(true);

        assertTrue(authz.canActOn(PEER_ADMIN, "user:reset-password", 99L));
    }

    @Test
    void aRefusedRedemptionIsRefusedEvenWithATicket() {
        authenticate(ADMIN);
        givenRanks(ADMIN, 50, PEER_ADMIN, 50);
        when(approvals.redeem(99L, ADMIN, "user:reset-password", PEER_ADMIN)).thenReturn(false);

        assertFalse(authz.canActOn(PEER_ADMIN, "user:reset-password", 99L));
    }

    /** Acting on a lower rank never needs a ticket, so none may be consumed for it. */
    @Test
    void theRankPathDoesNotConsumeATicket() {
        authenticate(ADMIN);
        givenRanks(ADMIN, 50, OFFICIAL, 10);

        assertTrue(authz.canActOn(OFFICIAL, "user:reset-password", 99L));
        verifyNoInteractions(approvals);
    }

    // ---------------------------------------------------------------- break-glass

    @Test
    void aLiveBreakGlassGrantSubstitutesForRank() {
        authenticate(ADMIN);
        givenRanks(ADMIN, 50, PEER_ADMIN, 50);
        when(breakGlass.isActive(ADMIN)).thenReturn(true);

        assertTrue(authz.canActOn(PEER_ADMIN, "user:reset-password"));
        verifyNoInteractions(approvals);
    }

    @Test
    void withoutALiveGrantAPeerActionIsStillRefused() {
        authenticate(ADMIN);
        givenRanks(ADMIN, 50, PEER_ADMIN, 50);
        when(breakGlass.isActive(ADMIN)).thenReturn(false);

        assertFalse(authz.canActOn(PEER_ADMIN, "user:reset-password"));
    }

    /** Break-glass unlocks rank only: it never hands out a capability the caller lacks. */
    @Test
    void aGrantDoesNotAddAPermissionTheCallerLacks() {
        authenticate(ADMIN);
        givenRanks(ADMIN, 50, PEER_ADMIN, 50);
        when(repository.permissionsFor(ADMIN)).thenReturn(List.of("user:read"));
        when(breakGlass.isActive(ADMIN)).thenReturn(true);

        assertFalse(authz.canActOn(PEER_ADMIN, "user:reset-password"));
    }

    private void givenRanks(long caller, int callerRank, long target, int targetRank) {
        when(repository.permissionsFor(caller))
                .thenReturn(List.of("user:manage", "user:reset-password"));
        when(repository.rankOf(caller)).thenReturn(callerRank);
        when(repository.rankOf(target)).thenReturn(targetRank);
    }

    private void authenticate(long userId) {
        User user = new User();
        user.setId(userId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }
}

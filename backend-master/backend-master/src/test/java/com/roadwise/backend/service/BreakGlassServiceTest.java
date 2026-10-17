package com.roadwise.backend.service;

import com.roadwise.backend.model.BreakGlassGrant;
import com.roadwise.backend.model.User;
import com.roadwise.backend.repository.AuthzRepository;
import com.roadwise.backend.repository.BreakGlassGrantRepository;
import com.roadwise.backend.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The constraints that make a self-issued emergency grant acceptable.
 *
 * <p>Everything here is a way the exception could quietly become permanent or silent:
 * an unbounded duration, an unexplained grant, a second live grant alongside the first, or
 * a grant handed to an account that never held the permission.
 */
class BreakGlassServiceTest {

    private static final long ADMIN = 1L;
    private static final long OTHER_ADMIN = 2L;
    private static final long OFFICIAL = 7L;

    private final BreakGlassGrantRepository grants = mock(BreakGlassGrantRepository.class);
    private final AuthzRepository authz = mock(AuthzRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final ActivityLogService activityLog = mock(ActivityLogService.class);
    private final NotificationService notifications = mock(NotificationService.class);

    private final BreakGlassService breakGlass =
            new BreakGlassService(grants, authz, users, activityLog, notifications);

    @AfterEach
    void clearPrincipal() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void aReasonIsRequired() {
        authenticate(ADMIN);
        when(authz.permissionsFor(ADMIN)).thenReturn(List.of(BreakGlassService.PERMISSION));

        assertThrows(IllegalArgumentException.class, () -> breakGlass.activate("too short", 30, null));
    }

    @Test
    void theDurationIsBounded() {
        authenticate(ADMIN);
        when(authz.permissionsFor(ADMIN)).thenReturn(List.of(BreakGlassService.PERMISSION));

        assertThrows(IllegalArgumentException.class,
                () -> breakGlass.activate("locked out of the office account", 1, null));
        assertThrows(IllegalArgumentException.class,
                () -> breakGlass.activate("locked out of the office account", 600, null));
    }

    @Test
    void onlyAnAccountThatHoldsThePermissionMayUseIt() {
        authenticate(OFFICIAL);
        when(authz.permissionsFor(OFFICIAL)).thenReturn(List.of("user:read"));

        assertThrows(IllegalArgumentException.class,
                () -> breakGlass.activate("locked out of the office account", 30, null));
    }

    @Test
    void aSecondLiveGrantIsRefused() {
        authenticate(ADMIN);
        when(authz.permissionsFor(ADMIN)).thenReturn(List.of(BreakGlassService.PERMISSION));
        when(grants.findFirstByGrantedToAndRevokedAtIsNullAndExpiresAtAfterOrderByExpiresAtDesc(
                eq(ADMIN), any(LocalDateTime.class)))
                .thenReturn(Optional.of(new BreakGlassGrant(ADMIN, "earlier", LocalDateTime.now().plusMinutes(10))));

        assertThrows(IllegalArgumentException.class,
                () -> breakGlass.activate("locked out of the office account", 30, null));
        verify(grants, never()).save(any());
    }

    @Test
    void activationIsTimeBoxedAndAlertsTheOtherAdministrators() {
        authenticate(ADMIN);
        when(authz.permissionsFor(ADMIN)).thenReturn(List.of(BreakGlassService.PERMISSION));
        when(grants.findFirstByGrantedToAndRevokedAtIsNullAndExpiresAtAfterOrderByExpiresAtDesc(
                eq(ADMIN), any(LocalDateTime.class)))
                .thenReturn(Optional.empty());
        when(grants.save(any(BreakGlassGrant.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(users.findByRoles(any())).thenReturn(List.of(user(OTHER_ADMIN), user(ADMIN)));
        when(users.findById(any())).thenReturn(Optional.of(user(ADMIN)));

        BreakGlassGrant grant = breakGlass.activate("locked out of the office account", 15, null);

        assertTrue(grant.getExpiresAt().isAfter(LocalDateTime.now().plusMinutes(10)));
        verify(notifications).sendNotification(eq(OTHER_ADMIN), any(), any(), eq("SECURITY"));
        verify(notifications, never()).sendNotification(eq(ADMIN), any(), any(), any());
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

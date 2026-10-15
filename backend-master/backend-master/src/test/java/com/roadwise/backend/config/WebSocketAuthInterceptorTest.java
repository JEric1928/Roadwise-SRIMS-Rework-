package com.roadwise.backend.config;

import com.roadwise.backend.model.Barangay;
import com.roadwise.backend.model.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.time.Instant;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The live-update socket's inbound rules, one test per rule that has to hold.
 *
 * <p>These are unit tests rather than an end-to-end socket test on purpose. A live test
 * cannot distinguish "the frame was refused" from "nothing was ever delivered", and in the
 * environment this was written in server-to-client WebSocket frames are not delivered at all
 * -- see the note on {@code PRODUCTION_READINESS.md} item 6. An absence assertion there would
 * pass against a completely unprotected server, which is worse than no test. The rules are
 * therefore asserted where they are decided: on the frame, before the broker sees it.
 *
 * <p>The rules under test, and the test that pins each one:
 * <ol>
 *   <li>Anonymous clients cannot SEND to broker destinations.</li>
 *   <li>Clients cannot publish dashboard refresh events themselves.</li>
 *   <li>SUBSCRIBE requires authentication and is restricted to allowed destinations.</li>
 *   <li>A permitted client cannot subscribe to data outside its own role or barangay.</li>
 *   <li>Expired or revoked credentials cannot continue sending or subscribing.</li>
 * </ol>
 *
 * <p>Each refusal is paired with the permitted form of the same frame, so an interceptor that
 * simply refuses everything cannot pass.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WebSocketAuthInterceptorTest {

    private static final String ADMIN_CHANNEL = LiveUpdateChannels.ADMIN;
    private static final String APP_DESTINATION = WebSocketConfig.APPLICATION_DESTINATION_PREFIX + "/reports";

    @Mock
    private SessionAuthenticator sessionAuthenticator;

    @Mock
    private com.roadwise.backend.repository.UserRepository userRepository;

    @Mock
    private LiveWebSocketSessionRegistry sessionRegistry;

    @InjectMocks
    private WebSocketAuthInterceptor interceptor;

    // ------------------------------------------------- 1. anonymous SEND is refused

    @Test
    void rule1_anonymousSendToBrokerDestination_isRefused() {
        StompHeaderAccessor accessor = accessor(StompCommand.SEND);
        accessor.setDestination(ADMIN_CHANNEL);

        assertThrows(AccessDeniedException.class, () -> interceptor.preSend(message(accessor), null));
    }

    @Test
    void rule1_anonymousSendToApplicationDestination_isRefusedToo() {
        StompHeaderAccessor accessor = accessor(StompCommand.SEND);
        accessor.setDestination(APP_DESTINATION);

        assertThrows(AccessDeniedException.class, () -> interceptor.preSend(message(accessor), null));
    }

    // ------------------------------------------------- 2. clients cannot publish the refresh

    /**
     * The amplification path. A client SEND addressed at a broker destination is broadcast by
     * the simple broker exactly as if application code had published it, so one socket could
     * otherwise drive every connected dashboard to refetch.
     */
    @Test
    void rule2_clientCannotPublishTheDashboardRefreshItself() {
        StompHeaderAccessor accessor = authenticated(StompCommand.SEND, activeUser("alice"), ADMIN_CHANNEL);

        assertThrows(AccessDeniedException.class, () -> interceptor.preSend(message(accessor), null));
    }

    @Test
    void rule2_clientCannotPublishToAnyOtherBrokerDestination() {
        StompHeaderAccessor accessor = authenticated(StompCommand.SEND, activeUser("alice"), "/topic/anything");

        assertThrows(AccessDeniedException.class, () -> interceptor.preSend(message(accessor), null));
    }

    @Test
    void rule2_clientMayAddressTheApplication() {
        StompHeaderAccessor accessor = authenticated(StompCommand.SEND, activeUser("alice"), APP_DESTINATION);

        Message<?> result = interceptor.preSend(message(accessor), null);

        assertEquals(APP_DESTINATION, StompHeaderAccessor.wrap(result).getDestination(),
                "a SEND under the application prefix is the permitted form of the frame above");
    }

    // ------------------------------------------------- 3. SUBSCRIBE needs auth + allow-list

    @Test
    void rule3_unauthenticatedSubscribe_isRefused() {
        StompHeaderAccessor accessor = accessor(StompCommand.SUBSCRIBE);
        accessor.setDestination(ADMIN_CHANNEL);

        assertThrows(AccessDeniedException.class, () -> interceptor.preSend(message(accessor), null));
    }

    @Test
    void rule3_subscribeToTheChannelForOnesRole_isAllowed() {
        StompHeaderAccessor accessor = authenticated(StompCommand.SUBSCRIBE, activeUser("alice"), ADMIN_CHANNEL);

        Message<?> result = interceptor.preSend(message(accessor), null);

        assertEquals(ADMIN_CHANNEL, StompHeaderAccessor.wrap(result).getDestination(),
                "an administrator must be able to read the administrator channel, or "
                        + "the permitted half of the rule above cannot be reached");
    }

    @Test
    void rule3_subscribeToUnlistedDestination_isRefused() {
        StompHeaderAccessor accessor = authenticated(StompCommand.SUBSCRIBE, activeUser("alice"), "/topic/other");

        assertThrows(AccessDeniedException.class, () -> interceptor.preSend(message(accessor), null));
    }

    /** The retired global topic must not remain readable just because it used to exist. */
    @Test
    void rule3_subscribeToTheRetiredGlobalTopic_isRefused() {
        StompHeaderAccessor accessor = authenticated(
                StompCommand.SUBSCRIBE, activeUser("alice"), "/topic/updates");

        assertThrows(AccessDeniedException.class, () -> interceptor.preSend(message(accessor), null));
    }

    @Test
    void rule3_subscribeToTheBrokerPrefixItself_isRefused() {
        StompHeaderAccessor accessor = authenticated(StompCommand.SUBSCRIBE, activeUser("alice"), "/topic/*");

        assertThrows(AccessDeniedException.class, () -> interceptor.preSend(message(accessor), null));
    }

    // ------------------------------------------------- 4. no cross-role / cross-barangay reads

    /**
     * The rule the single global topic could not express at all: everyone who could connect
     * could read everything. The allow/refuse matrix itself is settled in
     * {@link LiveUpdateChannelsTest}; what is pinned here is that the interceptor consults it
     * and applies it to the <em>authenticated</em> account rather than to anything the client
     * sent.
     */
    @Test
    void rule4_officialCannotSubscribeToAnotherBarangaysChannel() {
        User official = barangayOfficial("brgy-official", 7L);

        StompHeaderAccessor accessor = authenticated(
                StompCommand.SUBSCRIBE, official, LiveUpdateChannels.forBarangay(99L));

        assertThrows(AccessDeniedException.class, () -> interceptor.preSend(message(accessor), null));
    }

    @Test
    void rule4_officialMaySubscribeToItsOwnBarangaysChannel() {
        User official = barangayOfficial("brgy-official", 7L);

        StompHeaderAccessor accessor = authenticated(
                StompCommand.SUBSCRIBE, official, LiveUpdateChannels.forBarangay(7L));

        assertDoesNotThrow(() -> interceptor.preSend(message(accessor), null));
    }

    @Test
    void rule4_officialCannotReachAnotherBarangayByNamingItAwkwardly() {
        User official = barangayOfficial("brgy-official", 7L);

        for (String attempt : new String[]{
                LiveUpdateChannels.BARANGAY_PREFIX + "99/extra",
                LiveUpdateChannels.BARANGAY_PREFIX + "007",
                LiveUpdateChannels.BARANGAY_PREFIX + "99 ",
                LiveUpdateChannels.BARANGAY_PREFIX + "099"}) {
            StompHeaderAccessor accessor = authenticated(StompCommand.SUBSCRIBE, official, attempt);

            assertThrows(AccessDeniedException.class, () -> interceptor.preSend(message(accessor), null),
                    "should be refused: " + attempt);
        }
    }

    @Test
    void rule4_officialCannotReadTheRoleChannels() {
        User official = barangayOfficial("brgy-official", 7L);

        for (String channel : new String[]{LiveUpdateChannels.ADMIN, LiveUpdateChannels.ENGINEERING}) {
            StompHeaderAccessor accessor = authenticated(StompCommand.SUBSCRIBE, official, channel);

            assertThrows(AccessDeniedException.class, () -> interceptor.preSend(message(accessor), null),
                    "an official must not read " + channel);
        }
    }

    // ------------------------------------------------- 5. expired and revoked credentials

    @Test
    void rule5_revokedSessionCannotSubscribe() {
        User connectedAs = activeUser("alice");
        StompHeaderAccessor accessor = authenticated(StompCommand.SUBSCRIBE, connectedAs, ADMIN_CHANNEL);

        // Revoked after the socket opened: logout bumps the account's session version.
        User nowInDatabase = activeUser("alice");
        nowInDatabase.setSessionVersion(connectedAs.getSessionVersion() + 1);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(nowInDatabase));

        assertThrows(AccessDeniedException.class, () -> interceptor.preSend(message(accessor), null));
    }

    /**
     * SEND is re-checked on the same terms as SUBSCRIBE. Checking only whether a principal was
     * attached let a revoked session keep sending for as long as the socket stayed open.
     */
    @Test
    void rule5_revokedSessionCannotSend() {
        User connectedAs = activeUser("alice");
        StompHeaderAccessor accessor = authenticated(StompCommand.SEND, connectedAs, APP_DESTINATION);

        User nowInDatabase = activeUser("alice");
        nowInDatabase.setSessionVersion(connectedAs.getSessionVersion() + 1);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(nowInDatabase));

        assertThrows(AccessDeniedException.class, () -> interceptor.preSend(message(accessor), null));
    }

    @Test
    void rule5_expiredCredentialCannotSubscribe() {
        StompHeaderAccessor accessor = authenticated(
                StompCommand.SUBSCRIBE, activeUser("alice"), ADMIN_CHANNEL, expiredDeadline());

        assertThrows(AccessDeniedException.class, () -> interceptor.preSend(message(accessor), null));
    }

    @Test
    void rule5_expiredCredentialCannotSend() {
        StompHeaderAccessor accessor = authenticated(
                StompCommand.SEND, activeUser("alice"), APP_DESTINATION, expiredDeadline());

        assertThrows(AccessDeniedException.class, () -> interceptor.preSend(message(accessor), null));
    }

    @Test
    void rule5_suspendedAccountCannotSubscribe() {
        User connectedAs = activeUser("alice");
        StompHeaderAccessor accessor = authenticated(StompCommand.SUBSCRIBE, connectedAs, ADMIN_CHANNEL);

        User nowInDatabase = activeUser("alice");
        nowInDatabase.setStatus("Suspended");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(nowInDatabase));

        assertThrows(AccessDeniedException.class, () -> interceptor.preSend(message(accessor), null));
    }

    @Test
    void rule5_sessionWithNoKnownExpiry_isRefusedRatherThanTrusted() {
        StompHeaderAccessor accessor = authenticated(StompCommand.SUBSCRIBE, activeUser("alice"), ADMIN_CHANNEL);
        ((UsernamePasswordAuthenticationToken) accessor.getUser()).setDetails(null);

        assertThrows(AccessDeniedException.class, () -> interceptor.preSend(message(accessor), null));
    }

    // ------------------------------------------------- CONNECT establishes the session

    @Test
    void connectWithValidToken_authenticates() {
        User user = activeUser("alice");
        user.setId(12L);
        when(sessionAuthenticator.authenticate("good-token"))
                .thenReturn(new SessionAuthenticator.AuthenticatedSession(principalFor(user), 0L, futureDeadline()));

        StompHeaderAccessor accessor = accessor(StompCommand.CONNECT);
        accessor.addNativeHeader("Authorization", "Bearer good-token");

        Message<?> result = interceptor.preSend(message(accessor), null);

        StompHeaderAccessor after = StompHeaderAccessor.wrap(result);
        assertNotNull(after.getUser(), "a valid token must establish the session's principal");
        assertSame(user, ((org.springframework.security.core.Authentication) after.getUser()).getPrincipal());
        verify(sessionRegistry).associate("test-session", 12L);
    }

    @Test
    void connectWithoutToken_isRefused() {
        when(sessionAuthenticator.authenticate(any()))
                .thenThrow(rejected(SessionAuthenticator.SessionRejectedException.Reason.INVALID_TOKEN));

        assertThrows(AccessDeniedException.class,
                () -> interceptor.preSend(message(accessor(StompCommand.CONNECT)), null));
    }

    @Test
    void connectWithEmptyBearerToken_isRefused() {
        when(sessionAuthenticator.authenticate(any()))
                .thenThrow(rejected(SessionAuthenticator.SessionRejectedException.Reason.INVALID_TOKEN));

        StompHeaderAccessor accessor = accessor(StompCommand.CONNECT);
        accessor.addNativeHeader("Authorization", "Bearer ");

        assertThrows(AccessDeniedException.class, () -> interceptor.preSend(message(accessor), null));
    }

    @Test
    void connectWithRevokedSession_isRefused() {
        when(sessionAuthenticator.authenticate("stale-token"))
                .thenThrow(rejected(SessionAuthenticator.SessionRejectedException.Reason.SESSION_REVOKED));

        StompHeaderAccessor accessor = accessor(StompCommand.CONNECT);
        accessor.addNativeHeader("Authorization", "Bearer stale-token");

        assertThrows(AccessDeniedException.class, () -> interceptor.preSend(message(accessor), null));
    }

    // ----------------------------------------------------------------------- helpers

    private SessionAuthenticator.SessionRejectedException rejected(
            SessionAuthenticator.SessionRejectedException.Reason reason) {
        return new SessionAuthenticator.SessionRejectedException(reason, "refused");
    }

    private User activeUser(String username) {
        User user = new User();
        user.setUsername(username);
        user.setStatus("Active");
        user.setRole("ADMIN");
        when(userRepository.findByUsername(username)).thenReturn(Optional.of(user));
        return user;
    }

    private User barangayOfficial(String username, long barangayId) {
        User user = new User();
        user.setUsername(username);
        user.setStatus("Active");
        user.setRole("BARANGAY");
        Barangay barangay = new Barangay();
        barangay.setId(barangayId);
        user.setBarangay(barangay);
        when(userRepository.findByUsername(username)).thenReturn(Optional.of(user));
        return user;
    }

    private UsernamePasswordAuthenticationToken principalFor(User user) {
        return new UsernamePasswordAuthenticationToken(
                user, null, Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + user.getRole())));
    }

    private static long futureDeadline() {
        return Instant.now().getEpochSecond() + 3600;
    }

    private static long expiredDeadline() {
        return Instant.now().getEpochSecond() - 1;
    }

    private StompHeaderAccessor accessor(StompCommand command) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setSessionId("test-session");
        // StompSubProtocolHandler leaves the accessor mutable so interceptors can attach the
        // principal to the CONNECT frame. Without this the accessor is frozen once the message
        // is built and setUser fails -- a property of the test harness, not of the interceptor.
        accessor.setLeaveMutable(true);
        return accessor;
    }

    /**
     * A frame arriving on a session that already completed CONNECT: principal attached, and a
     * credential deadline carried on the session exactly as {@code authenticateConnection} sets
     * it in production. The stubbed database row is the account as it stands now.
     */
    private StompHeaderAccessor authenticated(StompCommand command, User connectedAs, String destination) {
        connectedAs.setSessionVersion(0L);
        return authenticated(command, connectedAs, destination, futureDeadline());
    }

    private StompHeaderAccessor authenticated(
            StompCommand command, User connectedAs, String destination, long deadline) {
        StompHeaderAccessor accessor = accessor(command);
        accessor.setDestination(destination);

        UsernamePasswordAuthenticationToken principal = principalFor(connectedAs);
        principal.setDetails(deadline);
        accessor.setUser(principal);

        when(userRepository.findByUsername(connectedAs.getUsername())).thenReturn(Optional.of(connectedAs));
        return accessor;
    }

    private Message<?> message(StompHeaderAccessor accessor) {
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}

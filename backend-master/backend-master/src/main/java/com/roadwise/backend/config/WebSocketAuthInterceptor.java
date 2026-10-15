package com.roadwise.backend.config;

import com.roadwise.backend.model.User;
import com.roadwise.backend.repository.UserRepository;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;

/**
 * Authorization for the live-update socket.
 *
 * <p>Why this exists: {@code @EnableWebSocketMessageBroker} with a simple broker is an open
 * relay by default. The handshake and every frame that follows are unauthenticated, and the
 * broker accepts a client {@code SEND} addressed to a broker destination exactly as it
 * accepts one from application code. A client could therefore publish to {@code /topic/updates}
 * and every connected dashboard would refetch -- one cheap frame in, unbounded work out. Any
 * client could equally subscribe to any topic, so the moment a topic carries real data it is
 * readable by anyone who can open a socket. The published payload is a literal ping today;
 * the authorization is written now because it has to be right before that changes, not after.
 *
 * <p>Three rules, applied to the inbound channel before the broker sees anything:
 * <ol>
 *   <li>{@code CONNECT} must carry a valid access token. Without one the socket is refused
 *       outright, so there is no such thing as an anonymous subscriber.</li>
 *   <li>{@code SEND} is limited to the application prefix. Clients may talk to the server;
 *       they may not publish onto the broker's own destinations. This is the rule that closes
 *       the amplification path, and it fails closed for destinations that do not exist yet.</li>
 *   <li>{@code SUBSCRIBE} is limited to the destinations {@link LiveUpdateChannels} recognizes
 *       and to the ones the authenticated account is entitled to. A new destination is readable
 *       only once a rule for it exists there, which is the intended cost: the alternative --
 *       allow by default and deny the bad ones -- cannot be reviewed, because nobody knows what
 *       the bad ones are until they are being read.</li>
 * </ol>
 *
 * <p>The handshake itself stays unauthenticated in {@link SecurityConfig}, and that is not an
 * oversight: a browser cannot set an {@code Authorization} header on a WebSocket upgrade, so
 * the token arrives on the STOMP {@code CONNECT} frame instead. The HTTP-level rule permits
 * the upgrade to happen; this interceptor is what decides whether the session survives it.
 */
@Component
public class WebSocketAuthInterceptor implements ChannelInterceptor {

    private final SessionAuthenticator sessionAuthenticator;
    private final UserRepository userRepository;
    private final LiveWebSocketSessionRegistry sessionRegistry;

    public WebSocketAuthInterceptor(
            SessionAuthenticator sessionAuthenticator,
            UserRepository userRepository,
            LiveWebSocketSessionRegistry sessionRegistry) {
        this.sessionAuthenticator = sessionAuthenticator;
        this.userRepository = userRepository;
        this.sessionRegistry = sessionRegistry;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            // Not a STOMP frame (broker-internal traffic, heartbeats). Nothing to authorize.
            return message;
        }

        switch (accessor.getCommand()) {
            case CONNECT, STOMP -> authenticateConnection(accessor);
            case SEND -> authorizeSend(accessor);
            case SUBSCRIBE -> authorizeSubscribe(accessor);
            default -> {
                // DISCONNECT, UNSUBSCRIBE, ACK and friends act only on the session the client
                // already authenticated, so they need no separate check.
            }
        }
        return message;
    }

    private void authenticateConnection(StompHeaderAccessor accessor) {
        String token = bearerToken(accessor.getFirstNativeHeader("Authorization"));
        try {
            SessionAuthenticator.AuthenticatedSession session = sessionAuthenticator.authenticate(token);
            AbstractAuthenticationToken authentication = session.authenticatedPrincipal();

            // Carry the credential's deadline on the session. Nothing re-reads the token after
            // CONNECT, so this is the only thing that can tell a later frame that the
            // credential behind the socket has expired.
            authentication.setDetails(session.expiresAtEpochSeconds());

            accessor.setUser(authentication);
            User user = (User) authentication.getPrincipal();
            sessionRegistry.associate(accessor.getSessionId(), user.getId());
        } catch (SessionAuthenticator.SessionRejectedException ex) {
            throw new AccessDeniedException("Live-update connection refused: " + ex.getMessage(), ex);
        }
    }

    private void authorizeSend(StompHeaderAccessor accessor) {
        // The same re-check as SUBSCRIBE. SEND previously only asked whether a principal was
        // attached, which meant a session revoked -- or a credential that expired -- after
        // CONNECT could keep sending for as long as the socket stayed open.
        requireSessionStillValid(accessor);

        String destination = accessor.getDestination();
        if (destination == null || !destination.startsWith(WebSocketConfig.APPLICATION_DESTINATION_PREFIX)) {
            throw new AccessDeniedException(
                    "Clients may only send to " + WebSocketConfig.APPLICATION_DESTINATION_PREFIX
                            + "** destinations. Publishing to a broker destination is refused.");
        }
    }

    private void authorizeSubscribe(StompHeaderAccessor accessor) {
        User connectedAs = requireSessionStillValid(accessor);

        String destination = accessor.getDestination();
        if (!LiveUpdateChannels.permits(connectedAs, destination)) {
            throw new AccessDeniedException("Subscription to " + destination + " is not permitted.");
        }
    }

    private void requireAuthenticated(StompHeaderAccessor accessor) {
        if (accessor.getUser() == null) {
            throw new AccessDeniedException("Live-update session is not authenticated.");
        }
    }

    /**
     * Re-checks the account behind an established socket on every subscription.
     *
     * <p>Authentication happens once, at CONNECT, and a socket can then stay open for hours.
     * Without this, logging out -- which revokes the session by bumping its version -- would
     * leave the socket authenticated until the browser tab closed, which is precisely the
     * window a revoked session is supposed to be closed in. The comparison is against the
     * snapshot taken at CONNECT rather than the token: {@link SessionAuthenticator} already
     * proved the token matched the account then, so any difference now is a revocation.
     */
    private User requireSessionStillValid(StompHeaderAccessor accessor) {
        requireAuthenticated(accessor);

        Authentication authentication = (Authentication) accessor.getUser();
        if (!(authentication.getPrincipal() instanceof User connectedAs)) {
            throw new AccessDeniedException("Live-update session is not authenticated.");
        }

        User current = userRepository.findByUsername(connectedAs.getUsername())
                .orElseThrow(() -> new MessageDeliveryException("Account no longer exists."));

        if ("Suspended".equalsIgnoreCase(current.getStatus()) || "Deactivated".equalsIgnoreCase(current.getStatus())) {
            throw new AccessDeniedException("Account status ineligible: " + current.getStatus());
        }

        if (!current.getSessionVersion().equals(connectedAs.getSessionVersion())) {
            throw new AccessDeniedException("Session has been revoked. Please log in again.");
        }

        Long expiresAt = authentication.getDetails() instanceof Long deadline ? deadline : null;
        if (expiresAt == null) {
            // A session that carries no deadline cannot be shown to be current. Refusing is the
            // only answer that does not amount to "trust it indefinitely".
            throw new AccessDeniedException("Live-update session has no known expiry.");
        }
        if (expiresAt <= Instant.now().getEpochSecond()) {
            throw new AccessDeniedException("Access token has expired. Please log in again.");
        }

        return connectedAs;
    }

    private static String bearerToken(String header) {
        if (!StringUtils.hasText(header) || !header.startsWith("Bearer ")) {
            return null;
        }
        return header.substring(7);
    }
}

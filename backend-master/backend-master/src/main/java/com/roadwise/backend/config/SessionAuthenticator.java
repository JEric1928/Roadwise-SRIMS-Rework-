package com.roadwise.backend.config;

import com.roadwise.backend.model.User;
import com.roadwise.backend.repository.UserRepository;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.Collections;

/**
 * Turns an access token into a validated {@link Authentication}, or refuses.
 *
 * <p>Two entry points accept a token -- the servlet filter for HTTP requests and the STOMP
 * interceptor for the live-update socket -- and both must apply the same rules. The
 * alternative, a copy of these checks in each place, is how a session-revocation check ends
 * up enforced on one transport and not the other: the copy is written once, both are
 * correct on the day, and a later fix is applied to whichever one the author was looking at.
 * Keeping one implementation means there is nothing to keep in sync.
 *
 * <p>Failure is reported as a typed exception rather than an HTTP status or a messaging
 * error, because the two callers must respond differently: HTTP answers with a status and
 * a JSON body, a STOMP frame is refused by throwing inside the inbound channel. Each
 * caller maps the exception to its own failure mode.
 */
@Component
public class SessionAuthenticator {

    private final JwtTokenProvider tokenProvider;
    private final UserRepository userRepository;

    public SessionAuthenticator(JwtTokenProvider tokenProvider, UserRepository userRepository) {
        this.tokenProvider = tokenProvider;
        this.userRepository = userRepository;
    }

    /**
     * @return the authenticated principal, its authorities, and the token's auth time and expiry
     * @throws SessionRejectedException when the token is absent, invalid, expired, belongs to
     *                                  an account that may no longer sign in, or was issued
     *                                  before the session was revoked
     */
    public AuthenticatedSession authenticate(String token) {
        if (token == null || token.isBlank() || !tokenProvider.validateToken(token)) {
            throw new SessionRejectedException(SessionRejectedException.Reason.INVALID_TOKEN,
                    "Missing or invalid access token.");
        }

        JwtTokenProvider.TokenClaims claims = tokenProvider.parseClaims(token);
        User user = userRepository.findByUsername(claims.username()).orElse(null);
        if (user == null) {
            // The account was deleted between issue and use. Indistinguishable from an
            // invalid token on purpose: the caller gains nothing from knowing which.
            throw new SessionRejectedException(SessionRejectedException.Reason.INVALID_TOKEN,
                    "Missing or invalid access token.");
        }

        if ("Suspended".equalsIgnoreCase(user.getStatus()) || "Deactivated".equalsIgnoreCase(user.getStatus())) {
            throw new SessionRejectedException(SessionRejectedException.Reason.ACCOUNT_INELIGIBLE,
                    "Account status ineligible: " + user.getStatus());
        }

        // Reject tokens issued before the latest session-version bump
        // (logout, password change/reset, emergency reset, handover).
        Long tokenSessionVersion = claims.sessionVersion();
        if (tokenSessionVersion == null || !tokenSessionVersion.equals(user.getSessionVersion())) {
            throw new SessionRejectedException(SessionRejectedException.Reason.SESSION_REVOKED,
                    "Session has been revoked. Please log in again.");
        }

        String roleName = user.getRole();
        if (roleName == null) {
            throw new SessionRejectedException(SessionRejectedException.Reason.INVALID_TOKEN,
                    "Missing or invalid access token.");
        }
        if (!roleName.startsWith("ROLE_")) {
            roleName = "ROLE_" + roleName;
        }

        AbstractAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                user,
                null,
                Collections.singletonList(new SimpleGrantedAuthority(roleName)));

        return new AuthenticatedSession(authentication, claims.authTime(), claims.expiresAt());
    }

    /**
     * The outcome of a successful token check.
     *
     * <p>The authentication time and the expiry travel with the principal rather than being
     * re-read from the token by each caller: the signature has already been verified here, and
     * a second parse would both cost another verification and risk the two disagreeing.
     *
     * <p>The expiry is needed by callers that authenticate once and then serve many later
     * actions -- the live-update socket is the case that motivated it. Nothing re-reads the
     * token on those, so without the deadline a credential that expired mid-session would go
     * on being accepted.
     */
    public record AuthenticatedSession(AbstractAuthenticationToken authenticatedPrincipal,
                                       Long authTimeEpochSeconds,
                                       Long expiresAtEpochSeconds) {
    }

    /** Why a token was refused, so each transport can render its own response. */
    public static class SessionRejectedException extends RuntimeException {

        public enum Reason {
            /** No token, malformed, bad signature, expired, or an account that no longer exists. */
            INVALID_TOKEN,
            /** A real account whose status forbids signing in. */
            ACCOUNT_INELIGIBLE,
            /** A well-formed token from a session that has since been revoked. */
            SESSION_REVOKED
        }

        private final Reason reason;

        public SessionRejectedException(Reason reason, String message) {
            super(message);
            this.reason = reason;
        }

        public Reason getReason() {
            return reason;
        }
    }
}

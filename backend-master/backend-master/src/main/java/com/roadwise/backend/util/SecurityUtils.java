package com.roadwise.backend.util;

import com.roadwise.backend.model.User;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Duration;
import java.time.Instant;

/**
 * Central helper for resolving the authenticated user from the SecurityContext.
 * Controllers must never trust a client-supplied userId for identity or audit purposes.
 */
public final class SecurityUtils {

    /**
     * Request attribute the JWT filter publishes the token's authentication time into.
     *
     * <p>Carried as a request attribute rather than re-parsed at each check: the filter
     * has already verified the signature, and a second parse per check would both cost a
     * signature verification and risk the two disagreeing.
     */
    public static final String AUTH_TIME_ATTRIBUTE = "roadwise.authTime";

    private SecurityUtils() {
    }

    /**
     * @return the currently authenticated {@link User}, or {@code null} when the
     *         request is unauthenticated (e.g. permitAll endpoints).
     */
    public static User getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        Object principal = auth.getPrincipal();
        if (principal instanceof User) {
            return (User) principal;
        }
        return null;
    }

    /**
     * @return the id of the currently authenticated user, or {@code null}.
     */
    public static Long getCurrentUserId() {
        User user = getCurrentUser();
        return user != null ? user.getId() : null;
    }

    /** Called by the authentication filter once the token has been verified. */
    public static void publishAuthTime(HttpServletRequest request, Long authTimeEpochSeconds) {
        if (authTimeEpochSeconds != null) {
            request.setAttribute(AUTH_TIME_ATTRIBUTE, authTimeEpochSeconds);
        }
    }

    /**
     * @return the epoch seconds at which this session last authenticated, or
     *         {@code null} when the token carried no such claim.
     */
    public static Long getAuthTime(HttpServletRequest request) {
        Object raw = request.getAttribute(AUTH_TIME_ATTRIBUTE);
        return raw instanceof Long value ? value : null;
    }

    /**
     * Whether the caller authenticated recently enough to perform a sensitive change.
     *
     * <p>Fail-closed on every uncertainty: no claim, a malformed value, or a timestamp
     * in the future all answer false. A future timestamp is not pedantry -- it is what a
     * badly skewed clock or a hand-crafted token would look like, and treating it as
     * "very recent" would be exactly the wrong reading.
     *
     * @param maxAge how long an authentication remains good enough
     */
    public static boolean isAuthenticatedRecently(HttpServletRequest request, Duration maxAge) {
        Long authTime = getAuthTime(request);
        if (authTime == null) {
            return false;
        }
        long now = Instant.now().getEpochSecond();
        if (authTime > now) {
            return false;
        }
        return (now - authTime) <= maxAge.getSeconds();
    }
}

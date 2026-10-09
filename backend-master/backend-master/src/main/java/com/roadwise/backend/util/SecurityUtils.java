package com.roadwise.backend.util;

import com.roadwise.backend.model.User;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Central helper for resolving the authenticated user from the SecurityContext.
 * Controllers must never trust a client-supplied userId for identity or audit purposes.
 */
public final class SecurityUtils {

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
}

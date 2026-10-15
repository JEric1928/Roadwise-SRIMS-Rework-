package com.roadwise.backend.config;

import com.roadwise.backend.util.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.time.Duration;

/**
 * Requires a recent re-authentication before a caller may change their own MFA setup.
 *
 * <p>Why this exists: enrolling a factor or replacing recovery codes is a durable change
 * to how an account is accessed. Being merely authenticated is not enough authorisation
 * for it. An access token lasts 15 minutes but is rotated indefinitely by {@code /refresh}
 * for the session's absolute lifetime, so a stolen token on its own was previously enough
 * to enrol an attacker-controlled factor -- after which access no longer depended on the
 * password at all.
 *
 * <p>Implemented as an interceptor on the path patterns rather than a check duplicated in
 * each controller method. The failure mode of the duplicated version is a new endpoint
 * added later that silently lacks the check, and that is not a mistake worth leaving
 * available.
 *
 * <p>Runs after the security filter chain (interceptors are dispatched by the servlet),
 * so the authentication time published by {@link JwtAuthenticationFilter} is already
 * present on the request.
 */
@Component
public class StepUpInterceptor implements HandlerInterceptor {

    /**
     * How long an authentication stays good enough to change factors.
     *
     * <p>Five minutes is a deliberate balance: long enough to sign in and go straight to
     * the security screen without being interrupted, short enough that a session left open
     * on an unattended machine cannot be used to grant lasting access.
     */
    public static final Duration MAX_AGE = Duration.ofMinutes(5);

    /** Machine-readable code so the client can prompt for a password rather than just fail. */
    public static final String STEP_UP_REQUIRED = "STEP_UP_REQUIRED";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        // CORS preflight carries no credentials and must not be answered with a 403.
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        // Static resources and error dispatches have no handler method to guard.
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }

        if (SecurityUtils.isAuthenticatedRecently(request, MAX_AGE)) {
            return true;
        }

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(
                "{\"error\":\"Please re-enter your password to continue.\","
                        + "\"code\":\"" + STEP_UP_REQUIRED + "\"}");
        return false;
    }
}

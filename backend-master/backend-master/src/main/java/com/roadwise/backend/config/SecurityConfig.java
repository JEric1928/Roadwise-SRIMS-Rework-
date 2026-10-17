package com.roadwise.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.util.List;

/**
 * Route-level access rules, and the list a coverage test checks new endpoints against.
 *
 * <p>The chain ends in {@code denyAll()}, so a path that is not named here is refused rather
 * than served. That inverts the previous default ({@code anyRequest().authenticated()},
 * where every newly mapped endpoint was reachable by every signed-in user until somebody
 * remembered to annotate it). The cost is that a new controller must be added to
 * {@link #AUTHENTICATED_PREFIXES} deliberately; {@code EndpointAuthorizationCoverageTest}
 * fails the build when one is not.
 *
 * <p>These rules answer "does this request need a session?". Who may do what is decided per
 * endpoint by {@code @PreAuthorize} against {@code @authz} (see
 * {@code AuthorizationService}), which is the only place that knows about rank and target
 * accounts.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * Paths reachable without a session. {@code /api/auth/**} stays public because the login
     * ceremony runs before a session exists and authenticates by challenge token instead;
     * the handful of sub-paths that do need a session are listed first in
     * {@link #STEP_UP_PATHS}. {@code /error} and {@code /} are permitted so that error
     * dispatches and the health probe are not turned into 403s by the catch-all deny.
     */
    static final List<String> PUBLIC_PATHS = List.of(
            "/api/health/**",
            "/health",
            "/health/",
            "/actuator/health",
            "/ws/**",
            "/ws-live/**",
            "/error",
            "/",
            "/api/auth/**");

    /**
     * Unauthenticated reads: the public barangay list, the road catalogue, and the dashboard
     * summary. Kept as GET-only rules so the write endpoints on the same prefixes still need
     * a session.
     */
    static final List<String> PUBLIC_GET_PATHS = List.of(
            "/api/barangays",
            "/api/roads/**",
            "/api/city-roads/**");

    /**
     * Prefixes that need a session but no route-level role. The per-endpoint
     * {@code @PreAuthorize} rules decide authorization from here.
     */
    static final List<String> AUTHENTICATED_PREFIXES = List.of(
            "/api/users/**",
            "/api/reports/**",
            "/api/activity-logs/**",
            "/api/notifications/**",
            "/api/cpdo/**",
            "/api/v1/forecasting/**",
            "/api/settings/**",
            "/api/barangays/**",
            "/api/roads/**",
            "/api/city-roads/**",
            // Maker-checker tickets. Ordering a privileged action is an administrative act,
            // so the prefix sits beside /api/users rather than in the public list.
            "/api/admin/**",
            // Report evidence. Uploaded images are caller-supplied content, not public
            // assets, so they keep requiring a session exactly as they did before.
            "/uploads/**",
            // Metrics and info were reachable by any authenticated caller; keep that rather
            // than turning a monitoring scrape into a 403. /actuator/health stays public.
            "/actuator/**");

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> {})
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // First match wins: the routes that act on the caller's own credentials must
                // be matched before the public /api/auth/** line below.
                .requestMatchers(STEP_UP_PATHS.toArray(String[]::new)).authenticated()
                .requestMatchers(PUBLIC_PATHS.toArray(String[]::new)).permitAll()
                .requestMatchers(HttpMethod.GET, PUBLIC_GET_PATHS.toArray(String[]::new)).permitAll()
                .requestMatchers(AUTHENTICATED_PREFIXES.toArray(String[]::new)).authenticated()
                // Fail closed. A new route is unreachable until it is listed above, and the
                // coverage test says so at build time instead of a caller finding out.
                .anyRequest().denyAll())
            // A refused administrative action answers with a body, not an empty 403: the
            // frontend's apiFetch already prefers body.error over its status-message table,
            // so this is what turns "request failed" into "request approval first" in the
            // toast the administrator sees. It also carries a code the client can branch on.
            .exceptionHandling(exceptions -> exceptions.accessDeniedHandler(
                    (request, response, denied) -> {
                        response.setStatus(jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN);
                        response.setContentType("application/json");
                        response.setCharacterEncoding("UTF-8");
                        response.getWriter().write(
                                "{\"error\":\"You are not allowed to perform this action."
                                        + " If it concerns a peer or superior account, request approval first.\","
                                        + "\"code\":\"ACCESS_DENIED\"}");
                    }))
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Sub-paths of {@code /api/auth} that act on the caller's own MFA enrolment, and so need
     * a real session even though the rest of that prefix is public.
     */
    static final List<String> STEP_UP_PATHS = List.of(
            "/api/auth/step-up",
            "/api/auth/mfa/overview",
            "/api/auth/mfa/factors/**",
            "/api/auth/mfa/recovery-codes");
}

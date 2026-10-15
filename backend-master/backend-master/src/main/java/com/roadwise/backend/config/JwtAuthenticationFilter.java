package com.roadwise.backend.config;

import com.roadwise.backend.model.User;
import com.roadwise.backend.repository.UserRepository;
import com.roadwise.backend.util.SecurityUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider tokenProvider;
    private final UserRepository userRepository;

    public JwtAuthenticationFilter(JwtTokenProvider tokenProvider, UserRepository userRepository) {
        this.tokenProvider = tokenProvider;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            String jwt = getJwtFromRequest(request);

            if (StringUtils.hasText(jwt) && tokenProvider.validateToken(jwt)) {
                JwtTokenProvider.TokenClaims claims = tokenProvider.parseClaims(jwt);
                String username = claims.username();

                User user = userRepository.findByUsername(username).orElse(null);
                if (user != null) {
                    if ("Suspended".equalsIgnoreCase(user.getStatus()) || "Deactivated".equalsIgnoreCase(user.getStatus())) {
                        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                        response.setContentType("application/json");
                        response.getWriter().write("{\"error\":\"Account status ineligible: " + user.getStatus() + "\"}");
                        return;
                    }

                    // Reject tokens issued before the latest session-version bump
                    // (logout, password change/reset, emergency reset, handover).
                    Long tokenSessionVersion = claims.sessionVersion();
                    if (tokenSessionVersion == null || !tokenSessionVersion.equals(user.getSessionVersion())) {
                        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                        response.setContentType("application/json");
                        response.getWriter().write("{\"error\":\"Session has been revoked. Please log in again.\",\"code\":\"SESSION_REVOKED\"}");
                        return;
                    }

                    String roleName = user.getRole();
                    if (!roleName.startsWith("ROLE_")) {
                        roleName = "ROLE_" + roleName;
                    }

                    UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                            user,
                            null,
                            Collections.singletonList(new SimpleGrantedAuthority(roleName))
                    );
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                    // Publish the token's authentication time for step-up checks. The
                    // signature is already verified at this point, so later checks read this
                    // attribute rather than paying for another signature verification.
                    SecurityUtils.publishAuthTime(request, claims.authTime());

                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            }
        } catch (Exception ex) {
            logger.error("Could not set user authentication in security context", ex);
        }

        filterChain.doFilter(request, response);
    }

    private String getJwtFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}

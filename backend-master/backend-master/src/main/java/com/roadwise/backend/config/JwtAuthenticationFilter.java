package com.roadwise.backend.config;

import com.roadwise.backend.util.SecurityUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final SessionAuthenticator sessionAuthenticator;

    public JwtAuthenticationFilter(SessionAuthenticator sessionAuthenticator) {
        this.sessionAuthenticator = sessionAuthenticator;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String jwt = getJwtFromRequest(request);

        if (StringUtils.hasText(jwt)) {
            try {
                SessionAuthenticator.AuthenticatedSession session = sessionAuthenticator.authenticate(jwt);
                AbstractAuthenticationToken authentication = session.authenticatedPrincipal();
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                // Publish the token's authentication time for step-up checks. The
                // signature is already verified at this point, so later checks read this
                // attribute rather than paying for another signature verification.
                SecurityUtils.publishAuthTime(request, session.authTimeEpochSeconds());

                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (SessionAuthenticator.SessionRejectedException ex) {
                if (writeRejection(response, ex)) {
                    return;
                }
                // An unusable token is not an error here: the request simply continues
                // unauthenticated and the filter chain answers 401/403 for protected paths.
                // Refusing outright would break the public endpoints that tolerate a stale
                // token in the browser's storage.
            } catch (Exception ex) {
                logger.error("Could not set user authentication in security context", ex);
            }
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Renders the two rejections that end the request, matching the statuses and bodies this
     * filter produced before the rules moved into {@link SessionAuthenticator}.
     *
     * @return true when the response was written and the chain must not continue
     */
    private boolean writeRejection(HttpServletResponse response, SessionAuthenticator.SessionRejectedException ex)
            throws IOException {
        switch (ex.getReason()) {
            case ACCOUNT_INELIGIBLE -> {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"" + ex.getMessage() + "\"}");
                return true;
            }
            case SESSION_REVOKED -> {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json");
                response.getWriter().write(
                        "{\"error\":\"" + ex.getMessage() + "\",\"code\":\"SESSION_REVOKED\"}");
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private String getJwtFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}

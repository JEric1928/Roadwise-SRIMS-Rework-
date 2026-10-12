package com.roadwise.backend.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Baseline browser security headers on every response.
 *
 * <p>A full Content-Security-Policy is deliberately not set here: the current
 * frontend still relies on inline scripts/handlers, so an honest CSP needs a
 * frontend refactor first (listed as a follow-up hardening task).</p>
 */
@Component
@Order(1)
public class SecurityHeadersFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (response instanceof HttpServletResponse httpResponse) {
            httpResponse.setHeader("X-Content-Type-Options", "nosniff");
            httpResponse.setHeader("X-Frame-Options", "DENY");
            httpResponse.setHeader("Referrer-Policy", "same-origin");
            httpResponse.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=(self)");
        }
        chain.doFilter(request, response);
    }
}

package com.roadwise.backend.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Server-side half of the Cloudflare Turnstile bot check.
 *
 * <p>A Turnstile widget in the browser proves nothing on its own: the value it produces is
 * just an opaque token, and only Cloudflare can tell a solved challenge from an invented
 * string. This service is the only thing that can turn that token into a decision, so the
 * login flow calls it before it ever looks at a password.</p>
 *
 * <h2>Fail closed</h2>
 *
 * <p>Every failure mode below refuses the login: an absent token, a missing
 * {@code turnstile.secret-key}, a rejected token, an unreachable Cloudflare endpoint, a
 * mismatched hostname or action. The alternative — allowing the request through when the
 * check cannot be performed — makes the feature removable by anyone who can make
 * {@code challenges.cloudflare.com} unresolvable for the server, which is cheaper than
 * solving the challenge. An operator who deploys this build without
 * {@code TURNSTILE_SECRET_KEY} therefore gets working logins for nobody; the startup warning
 * and the log line on each refusal say exactly which variable is missing.</p>
 *
 * <h2>What is checked beyond {@code success}</h2>
 *
 * <p>Cloudflare's verdict alone is not enough. A token minted for a different site, or for a
 * different action on this site, still verifies — it just was not earned by the form it is
 * being presented to. {@code turnstile.expected-hostname} (the <em>frontend's</em> host: the
 * widget runs there and the API may live elsewhere) and {@code turnstile.expected-action}
 * pin the token to this login form. Both are optional, and both fail closed when set and not
 * matched.</p>
 *
 * <h2>Why the token is not stored</h2>
 *
 * <p>Turnstile tokens are single-use and short-lived; Cloudflare's own replay protection is
 * what makes "verify, then use" safe, so there is nothing to persist and nothing to clean
 * up. The frontend renders a fresh challenge after every attempt (see
 * {@code js/turnstile.js}).</p>
 */
@Service
public class TurnstileService {

    private static final Logger log = LoggerFactory.getLogger(TurnstileService.class);

    private static final String DEFAULT_VERIFY_URL =
            "https://challenges.cloudflare.com/turnstile/v0/siteverify";

    /**
     * Cloudflare's documented "always passes" site key pair. They are useful only for local
     * development; against a real secret key a test token is rejected like any other. Kept
     * here as documentation of what the frontend falls back to on loopback.
     */
    private static final String ALWAYS_PASS_TEST_SITE_KEY = "1x00000000000000000000AA";

    @Value("${turnstile.secret-key:}")
    private String secretKey;

    @Value("${turnstile.verify-url:" + DEFAULT_VERIFY_URL + "}")
    private String verifyUrl;

    /**
     * Comma-separated list of hostnames the widget is allowed to have been solved on. Empty
     * disables the check; set it to the frontend origin's host (no scheme, no port), e.g.
     * {@code roadwise-csjdm.com} or {@code localhost}.
     */
    @Value("${turnstile.expected-hostname:}")
    private String expectedHostname;

    /**
     * Action the widget was rendered with, as reported back by Cloudflare. Empty disables
     * the check.
     */
    @Value("${turnstile.expected-action:}")
    private String expectedAction;

    private final RestTemplate restTemplate;

    public TurnstileService() {
        // Siteverify sits on the login path, so the timeouts are deliberately short: a slow
        // Cloudflare must degrade into a refused login quickly, not into a held request
        // thread behind every other login. 3s connect / 5s read matches what a real
        // verification takes (tens of milliseconds) with room for a slow round trip.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(5000);
        this.restTemplate = new RestTemplate(factory);
    }

    @PostConstruct
    void warnWhenUnconfigured() {
        if (isBlank(secretKey)) {
            log.warn("[TURNSTILE] turnstile.secret-key / TURNSTILE_SECRET_KEY is not set. "
                    + "Login is fail-closed and will refuse every attempt with 403 until it is "
                    + "configured. Cloudflare's always-pass test secret "
                    + "(1x0000000000000000000000000000000AA) is for local development only.");
        }
    }

    /**
     * Verifies one widget token.
     *
     * @param token    the {@code turnstileToken} submitted with the login body
     * @param remoteIp the caller's address, forwarded to Cloudflare as {@code remoteip} so
     *                 that a token solved on another network is not accepted here; may be
     *                 null
     * @return the verdict, never null
     */
    public Result verify(String token, String remoteIp) {
        if (isBlank(token)) {
            return Result.refuse("missing-input-response");
        }
        if (isBlank(secretKey)) {
            // Misconfiguration, not an attack. Still a refusal: see the class comment.
            log.error("[TURNSTILE] Refusing login: turnstile.secret-key / TURNSTILE_SECRET_KEY "
                    + "is not configured.");
            return Result.refuse("not-configured");
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("secret", secretKey.trim());
        form.add("response", token.trim());
        if (!isBlank(remoteIp)) {
            // request.getRemoteAddr() is already proxy-aware: server.forward-headers-strategy
            // = framework resolves X-Forwarded-For, so this is the client, not the load
            // balancer.
            form.add("remoteip", remoteIp.trim());
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        Map<String, Object> body;
        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    verifyUrl,
                    HttpMethod.POST,
                    new HttpEntity<>(form, headers),
                    new ParameterizedTypeReference<Map<String, Object>>() {});
            body = response.getBody();
        } catch (Exception ex) {
            // Includes connection refused, DNS failure, TLS failure and timeouts. All of them
            // are "cannot verify", and cannot-verify is a refusal.
            log.warn("[TURNSTILE] Verification request failed ({}); refusing login.", ex.toString());
            return Result.refuse("verification-unavailable");
        }

        if (body == null) {
            log.warn("[TURNSTILE] Cloudflare returned an empty verification body; refusing login.");
            return Result.refuse("empty-verification-response");
        }

        if (!Boolean.TRUE.equals(body.get("success"))) {
            String reason = describeErrorCodes(body.get("error-codes"));
            log.info("[TURNSTILE] Token rejected by Cloudflare ({}).", reason);
            return Result.refuse(reason);
        }

        String hostnameMismatch = checkHostname(body.get("hostname"));
        if (hostnameMismatch != null) {
            log.warn("[TURNSTILE] {}", hostnameMismatch);
            return Result.refuse("hostname-mismatch");
        }

        String actionMismatch = checkAction(body.get("action"));
        if (actionMismatch != null) {
            log.warn("[TURNSTILE] {}", actionMismatch);
            return Result.refuse("action-mismatch");
        }

        return Result.pass();
    }

    /** Visible for tests and for documentation of the loopback fallback in js/turnstile.js. */
    public static String alwaysPassTestSiteKey() {
        return ALWAYS_PASS_TEST_SITE_KEY;
    }

    private String checkHostname(Object reported) {
        if (isBlank(expectedHostname)) {
            return null;
        }
        List<String> allowed = Arrays.stream(expectedHostname.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .toList();
        if (allowed.isEmpty()) {
            return null;
        }
        if (!(reported instanceof String actual) || actual.isBlank()) {
            return "Token carries no hostname but turnstile.expected-hostname is set; refusing login.";
        }
        if (!allowed.contains(actual.toLowerCase(Locale.ROOT))) {
            return "Token was solved on hostname '" + actual + "' which is not in "
                    + "turnstile.expected-hostname; refusing login.";
        }
        return null;
    }

    private String checkAction(Object reported) {
        if (isBlank(expectedAction)) {
            return null;
        }
        if (!(reported instanceof String actual) || actual.isBlank()) {
            // The widget was rendered without an action, so Cloudflare has nothing to report.
            // A configured expectation that cannot be confirmed is not confirmed.
            return "Token carries no action but turnstile.expected-action=" + expectedAction
                    + " is set; refusing login. Render the widget with the same action.";
        }
        if (!expectedAction.trim().equals(actual)) {
            return "Token action '" + actual + "' does not match turnstile.expected-action="
                    + expectedAction + "; refusing login.";
        }
        return null;
    }

    private static String describeErrorCodes(Object errorCodes) {
        if (errorCodes instanceof List<?> codes && !codes.isEmpty()) {
            return codes.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("unknown");
        }
        return "unknown";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Outcome of one verification. {@code reason} is a short machine-ish tag: Cloudflare's
     * own error codes when it answered, otherwise one of {@code missing-input-response},
     * {@code not-configured}, {@code verification-unavailable},
     * {@code empty-verification-response}, {@code hostname-mismatch},
     * {@code action-mismatch}. It goes into the activity log, never to the caller.
     */
    public record Result(boolean success, String reason) {

        static Result pass() {
            return new Result(true, null);
        }

        static Result refuse(String reason) {
            return new Result(false, reason);
        }
    }
}

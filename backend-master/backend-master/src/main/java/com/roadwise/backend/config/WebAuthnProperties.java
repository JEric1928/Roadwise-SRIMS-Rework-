package com.roadwise.backend.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Passive/passkey (WebAuthn) relying-party configuration.
 *
 * <p>Follows the {@code mfa.encryption-key} precedent exactly, for the same reason:
 * <ul>
 *   <li><b>Unset</b> — the feature is inert. Passkey endpoints answer 503, the factor is
 *       never offered, and nothing about login, TOTP or email OTP changes. Enabling
 *       passkeys is an explicit deployment decision, not a silent default.</li>
 *   <li><b>Malformed</b> — startup fails. A half-configured relying party (an rp-id with
 *       a scheme stuck on it, an origin with a path, an http origin on a public host)
 *       would fail every ceremony at runtime with an error nobody reads until someone is
 *       locked out, so it is rejected here while the deployer is still watching.</li>
 * </ul>
 */
@Component
public class WebAuthnProperties {

    /**
     * Bare hostname: lowercase labels separated by dots, or {@code localhost} for
     * development. Rejects scheme, path, port, uppercase, trailing dot and spaces --
     * every mistake a copy-pasted URL would introduce.
     */
    private static final Pattern RP_ID = Pattern.compile(
            "localhost|[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+");

    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "::1", "[::1]");

    private final String rpId;
    private final Set<String> allowedOrigins;
    private final boolean configured;

    public WebAuthnProperties(
            @Value("${webauthn.rp-id:}") String rpId,
            @Value("${webauthn.allowed-origins:}") String allowedOrigins) {
        this.rpId = rpId == null ? "" : rpId.trim().toLowerCase(Locale.ROOT);
        this.allowedOrigins = normalizeOrigins(allowedOrigins);
        boolean rpSet = !this.rpId.isEmpty();
        boolean originsSet = !this.allowedOrigins.isEmpty();
        if (rpSet != originsSet) {
            throw new IllegalStateException(
                    "Half-configured passkey settings: webauthn.rp-id and webauthn.allowed-origins "
                            + "must be set together (rp-id " + (rpSet ? "is set, origins are not"
                            : "is missing while origins are set") + ").");
        }
        this.configured = rpSet;
        // Validating eagerly even when configured=false keeps malformed-but-present values
        // fatal at startup rather than discovered at the first ceremony.
        if (rpSet && !RP_ID.matcher(this.rpId).matches()) {
            throw new IllegalStateException(
                    "webauthn.rp-id must be a bare hostname like \"roadwise.example.gov.ph\" "
                            + "(or \"localhost\" for development), got: " + rpId);
        }
    }

    /** @return true when both rp-id and origins are present (and were validated). */
    public boolean isConfigured() {
        return configured;
    }

    /** @return the bare hostname passkeys are scoped to. */
    public String getRpId() {
        return rpId;
    }

    /** @return the exact origin strings (scheme://host[:port]) ceremonies may come from. */
    public Set<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    /**
     * Parses and validates the comma-separated origin list.
     *
     * <p>Each entry must be an absolute origin: scheme + host, optional non-default port,
     * no path beyond "/", no query or fragment. https is required except on loopback
     * hosts, where http is allowed because the development frontend serves from it.
     * Values are normalised the way browsers report them in clientDataJSON (lowercase
     * scheme and host, default port omitted, no trailing slash), because that string is
     * what webauthn4j compares against.
     */
    private static Set<String> normalizeOrigins(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        Set<String> origins = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String value = part.trim();
            if (value.isEmpty()) {
                continue;
            }
            URI uri;
            try {
                uri = new URI(value);
            } catch (URISyntaxException e) {
                throw new IllegalStateException("webauthn.allowed-origins entry is not a valid origin: " + value);
            }
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (!"https".equals(scheme) && !("http".equals(scheme) && LOOPBACK.contains(host))) {
                throw new IllegalStateException(
                        "webauthn.allowed-origins entry must be https (or http on loopback hosts only): " + value);
            }
            if (host.isEmpty()) {
                throw new IllegalStateException("webauthn.allowed-origins entry has no host: " + value);
            }
            String path = uri.getPath();
            if (path != null && !path.isEmpty() && !"/".equals(path)) {
                throw new IllegalStateException(
                        "webauthn.allowed-origins entries must be origins, not URLs (no path): " + value);
            }
            if (uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalStateException(
                        "webauthn.allowed-origins entries must be origins (no query or fragment): " + value);
            }
            boolean defaultPort = uri.getPort() == -1
                    || ("https".equals(scheme) && uri.getPort() == 443)
                    || ("http".equals(scheme) && uri.getPort() == 80);
            origins.add(scheme + "://" + host + (defaultPort ? "" : ":" + uri.getPort()));
        }
        if (origins.isEmpty()) {
            throw new IllegalStateException("webauthn.allowed-origins was provided but contained no origins.");
        }
        return Set.copyOf(origins);
    }

    @PostConstruct
    void validateAtStartup() {
        // Constructor already threw on anything malformed; this exists so the intent --
        // config is checked at startup, never at first use -- is visible at the call site.
        if (configured) {
            // Defensive re-check that the streams above produced what ceremonies need.
            if (!RP_ID.matcher(rpId).matches() || allowedOrigins.stream().anyMatch(o -> !o.contains("://"))) {
                throw new IllegalStateException("Passkey configuration failed validation.");
            }
        }
    }

}
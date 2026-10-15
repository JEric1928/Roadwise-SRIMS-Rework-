package com.roadwise.backend.config;

import com.roadwise.backend.model.User;
import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.time.Instant;

@Component
public class JwtTokenProvider {

    @Value("${jwt.secret:}")
    private String jwtSecret;

    @Value("${jwt.access-token-expiration-ms:900000}") // 15 minutes default
    private long jwtExpirationMs;

    /**
     * Fails fast at startup when no signing secret is configured, instead of
     * silently falling back to a well-known checked-in key. Deployment must
     * supply a unique, high-entropy secret via the JWT_SECRET environment
     * variable (or jwt.secret property).
     */
    @PostConstruct
    void validateConfiguration() {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException(
                    "jwt.secret is not configured. Set the JWT_SECRET environment variable to a unique, " +
                    "high-entropy Base64 value before starting the application.");
        }
        // Validate that the secret is decodable and long enough for HS256.
        try {
            Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtSecret));
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "jwt.secret is invalid: expected a Base64-encoded value of at least 256 bits (32 bytes).", e);
        }
    }

    private SecretKey getSigningKey() {
        byte[] keyBytes = Decoders.BASE64.decode(jwtSecret);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * Claim carrying the moment the user last actually proved their identity (epoch
     * seconds). Named after the OpenID Connect claim of the same meaning rather than
     * invented, so there is no ambiguity about what it denotes.
     */
    public static final String AUTH_TIME_CLAIM = "auth_time";

    /** Issues an access token whose authentication is considered to be right now. */
    public String generateAccessToken(User user) {
        return generateAccessToken(user, Instant.now().getEpochSecond());
    }

    /**
     * Issues an access token carrying an explicit authentication time.
     *
     * <p>The explicit form exists for refresh. A rotated token must inherit the original
     * authentication moment rather than stamping a new one -- otherwise a caller holding
     * only refresh tokens could mint a "fresh" session indefinitely and the freshness
     * check would protect nothing.
     */
    public String generateAccessToken(User user, long authTimeEpochSeconds) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("id", user.getId());
        claims.put("role", user.getRole());
        // Security stamp: the filter rejects the token when it no longer matches
        // the account's current session version (logout/password change).
        claims.put("sv", user.getSessionVersion());
        claims.put(AUTH_TIME_CLAIM, authTimeEpochSeconds);
        if (user.getBarangay() != null) {
            claims.put("barangayId", user.getBarangay().getId());
        }

        return Jwts.builder()
                .claims(claims)
                .subject(user.getUsername())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + jwtExpirationMs))
                .signWith(getSigningKey())
                .compact();
    }

    public String getUsernameFromToken(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getSubject();
    }

    /**
     * @return the session-version security stamp carried by the token, or
     *         {@code null} for legacy tokens issued before the claim existed
     *         (which are treated as revoked).
     */
    public Long getSessionVersionFromToken(String token) {
        Object raw = Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .get("sv");
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.valueOf(raw.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * @return the authentication time carried by the token, or {@code null} when absent.
     *
     * <p>Absence is answered with null rather than "now": tokens minted before this claim
     * existed must never satisfy a freshness check, matching how a missing session-version
     * stamp is already treated as revoked.
     */
    public Long getAuthTimeFromToken(String token) {
        Object raw = Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .get(AUTH_TIME_CLAIM);
        return toEpochSeconds(raw);
    }

    /** Coerces the numeric claim forms a JWT library may hand back. */
    private static Long toEpochSeconds(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.valueOf(raw.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Everything an authenticating caller needs, read in a single parse.
     *
     * <p>Each parse verifies the signature, so reading the fields through the
     * individual getters would verify the same token once per field.
     *
     * <p>{@code expiresAt} is carried rather than re-derived because a long-lived socket
     * authenticates once and then has to decide, on later frames, whether the credential it
     * accepted has since expired. That check needs the deadline, not another parse.
     */
    public record TokenClaims(String username, Long sessionVersion, Long authTime, Long expiresAt) {
    }

    /** @throws JwtException when the token is malformed, tampered with, or expired. */
    public TokenClaims parseClaims(String token) {
        Claims payload = Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return new TokenClaims(
                payload.getSubject(),
                toEpochSeconds(payload.get("sv")),
                toEpochSeconds(payload.get(AUTH_TIME_CLAIM)),
                payload.getExpiration() != null ? payload.getExpiration().getTime() / 1000 : null);
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parser().verifyWith(getSigningKey()).build().parseSignedClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }
}

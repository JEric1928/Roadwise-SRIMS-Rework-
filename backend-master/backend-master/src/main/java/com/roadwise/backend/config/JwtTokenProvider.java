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

    public String generateAccessToken(User user) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("id", user.getId());
        claims.put("role", user.getRole());
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

    public boolean validateToken(String token) {
        try {
            Jwts.parser().verifyWith(getSigningKey()).build().parseSignedClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }
}

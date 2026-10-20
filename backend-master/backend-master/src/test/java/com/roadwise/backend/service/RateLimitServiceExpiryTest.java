package com.roadwise.backend.service;

import com.roadwise.backend.model.LoginRateLimit;
import com.roadwise.backend.repository.LoginRateLimitRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

@SpringBootTest
@ActiveProfiles("test")
class RateLimitServiceExpiryTest {

    private static final String KEY = "USER:expired-window-with-live-penalty";

    @Autowired
    private RateLimitService rateLimitService;

    @Autowired
    private LoginRateLimitRepository loginRateLimitRepository;

    @BeforeEach
    void setUp() {
        loginRateLimitRepository.deleteAll();
    }

    private void seedExpiredWindowWithLivePenalty() {
        LoginRateLimit expired = new LoginRateLimit(KEY);
        expired.setAttemptCount(9);
        expired.setFirstAttemptAt(LocalDateTime.now().minusHours(1));
        expired.setLastAttemptAt(LocalDateTime.now().minusHours(1));
        expired.setBlockedUntil(LocalDateTime.now().plusHours(1));
        loginRateLimitRepository.saveAndFlush(expired);
    }

    @Test
    void expiredFailureWindowIsResetInPlaceRatherThanReinserted() {
        seedExpiredWindowWithLivePenalty();

        assertDoesNotThrow(() -> rateLimitService.recordFailure(KEY),
                "an expired counter must be reset in place; re-inserting collides with the unique key");

        LoginRateLimit stored = loginRateLimitRepository.findByKeyIdentifier(KEY).orElseThrow();
        assertEquals(1, stored.getAttemptCount(),
                "the expired count of 9 must be discarded, leaving a single fresh failure");
        assertNull(stored.getBlockedUntil(),
                "window expiry must discard the live penalty it inherited");
    }

    @Test
    void expiredRequestWindowIsResetInPlaceRatherThanReinserted() {
        seedExpiredWindowWithLivePenalty();

        assertDoesNotThrow(() -> rateLimitService.recordRequest(KEY),
                "the resend path shares the same expired-row branch");

        LoginRateLimit stored = loginRateLimitRepository.findByKeyIdentifier(KEY).orElseThrow();
        assertEquals(1, stored.getAttemptCount(), "the request counter must restart at 1");
    }

    @Test
    void windowExpiryOverridesALivePenaltyOnTheReadPath() {
        seedExpiredWindowWithLivePenalty();
        assertFalse(loginRateLimitRepository.findByKeyIdentifier(KEY).isEmpty(),
                "precondition: the seeded row must exist before the read path can delete it");

        assertFalse(rateLimitService.isBlocked(KEY),
                "a penalty still in force must not outlive its counting window");
    }
}

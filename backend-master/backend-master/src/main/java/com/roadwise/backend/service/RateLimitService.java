package com.roadwise.backend.service;

import com.roadwise.backend.model.LoginRateLimit;
import com.roadwise.backend.repository.LoginRateLimitRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Shared throttle used by login, password recovery and sensitive account
 * operations (password change, email change, office handover).
 *
 * <p>Counters age out after {@link #WINDOW} instead of blocking forever, and
 * failures start at 0 (the old off-by-one from the column default of 1).</p>
 */
@Service
public class RateLimitService {

    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final int FAILURE_THRESHOLD = 5;
    private static final long MAX_PENALTY_SECONDS = 900; // 15 minutes

    @Autowired
    private LoginRateLimitRepository loginRateLimitRepository;

    private boolean isWindowExpired(LoginRateLimit rateLimit) {
        return rateLimit.getFirstAttemptAt() != null
                && rateLimit.getFirstAttemptAt().isBefore(LocalDateTime.now().minus(WINDOW));
    }

    /** @return true when the key is currently in a penalty window. */
    public boolean isBlocked(String key) {
        LoginRateLimit rateLimit = loginRateLimitRepository.findByKeyIdentifier(key).orElse(null);
        if (rateLimit == null) {
            return false;
        }
        if (isWindowExpired(rateLimit)) {
            loginRateLimitRepository.delete(rateLimit);
            return false;
        }
        return rateLimit.getBlockedUntil() != null && LocalDateTime.now().isBefore(rateLimit.getBlockedUntil());
    }

    /** Records a failed attempt and applies an exponential penalty past the threshold. */
    public void recordFailure(String key) {
        LoginRateLimit rateLimit = loginRateLimitRepository.findByKeyIdentifier(key).orElse(null);
        if (rateLimit == null || isWindowExpired(rateLimit)) {
            rateLimit = new LoginRateLimit(key);
            rateLimit.setAttemptCount(0);
            rateLimit.setFirstAttemptAt(LocalDateTime.now());
        }
        rateLimit.setAttemptCount(rateLimit.getAttemptCount() + 1);
        rateLimit.setLastAttemptAt(LocalDateTime.now());
        if (rateLimit.getAttemptCount() > FAILURE_THRESHOLD) {
            long penaltySeconds = (long) Math.pow(2, rateLimit.getAttemptCount() - FAILURE_THRESHOLD);
            rateLimit.setBlockedUntil(LocalDateTime.now().plusSeconds(Math.min(penaltySeconds, MAX_PENALTY_SECONDS)));
        }
        loginRateLimitRepository.save(rateLimit);
    }

    /**
     * Fixed-cooldown throttle used for resend-style requests (recovery codes,
     * email-change codes): blocked while {@code cooldown} has not elapsed since
     * the last request, or once {@code maxPerWindow} requests happened in the
     * window.
     */
    public boolean isRequestThrottled(String key, Duration cooldown, int maxPerWindow) {
        LoginRateLimit rateLimit = loginRateLimitRepository.findByKeyIdentifier(key).orElse(null);
        if (rateLimit == null || isWindowExpired(rateLimit)) {
            return false;
        }
        if (rateLimit.getLastAttemptAt() != null
                && rateLimit.getLastAttemptAt().isAfter(LocalDateTime.now().minus(cooldown))) {
            return true;
        }
        return rateLimit.getAttemptCount() >= maxPerWindow;
    }

    public void recordRequest(String key) {
        LoginRateLimit rateLimit = loginRateLimitRepository.findByKeyIdentifier(key).orElse(null);
        if (rateLimit == null || isWindowExpired(rateLimit)) {
            rateLimit = new LoginRateLimit(key);
            rateLimit.setAttemptCount(0);
            rateLimit.setFirstAttemptAt(LocalDateTime.now());
        }
        rateLimit.setAttemptCount(rateLimit.getAttemptCount() + 1);
        rateLimit.setLastAttemptAt(LocalDateTime.now());
        loginRateLimitRepository.save(rateLimit);
    }

    public void clear(String key) {
        loginRateLimitRepository.findByKeyIdentifier(key).ifPresent(loginRateLimitRepository::delete);
    }
}

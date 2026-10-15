package com.roadwise.backend.service;

import com.roadwise.backend.model.LoginRateLimit;
import com.roadwise.backend.repository.LoginRateLimitRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Shared throttle used by login, password recovery and sensitive account
 * operations (password change, email change, office handover).
 *
 * <p>Counters age out after {@link #WINDOW} instead of blocking forever, and
 * failures start at 0 (the old off-by-one from the column default of 1).</p>
 *
 * <h2>Concurrency</h2>
 *
 * <p>The counters live in {@code login_rate_limits}, so what has to be atomic is
 * the <em>database</em> read-modify-write, not an in-process one. This class
 * previously used {@code synchronized} methods, which only serialise threads
 * inside one JVM: with two API instances, both could read
 * {@code attempt_count = 3}, both write {@code 4}, and the effective limit would
 * double — under exactly the conditions a credential-stuffing attempt creates.
 * The lock now comes from {@code SELECT ... FOR UPDATE}
 * ({@link LoginRateLimitRepository#findWithLockByKeyIdentifier}), which works
 * across instances, and {@code synchronized} is gone rather than left in place as
 * false reassurance.</p>
 *
 * <h2>Why {@link TransactionTemplate} rather than {@code @Transactional}</h2>
 *
 * <p>The first attempt for a key has no row yet, so it is an INSERT, and two
 * instances racing on that first attempt will both try to insert against a UNIQUE
 * column. One of them loses. Recovery has to happen in a <em>fresh</em>
 * transaction: the losing transaction has already failed its flush and is marked
 * rollback-only, so retrying inside it would fail again — with a confusing
 * {@code UnexpectedRollbackException} instead of the real outcome. Annotating the
 * public methods with {@code @Transactional} cannot express "retry in a new
 * transaction", because the retry would run in the same one. Each unit of work is
 * therefore an explicit template call, and only the outer call is retried.</p>
 */
@Service
public class RateLimitService {

    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final int FAILURE_THRESHOLD = 5;
    private static final long MAX_PENALTY_SECONDS = 900; // 15 minutes

    @Autowired
    private LoginRateLimitRepository loginRateLimitRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private boolean isWindowExpired(LoginRateLimit rateLimit) {
        return rateLimit.getFirstAttemptAt() != null
                && rateLimit.getFirstAttemptAt().isBefore(LocalDateTime.now().minus(WINDOW));
    }

    /**
     * Runs {@code work} in its own transaction, retrying once when a concurrent
     * first attempt wins the INSERT race.
     *
     * <p>The retry is unconditional on the second attempt: by then the row exists
     * (the other writer created it), so the locked read succeeds and no INSERT is
     * attempted. A second failure is a real error and propagates.</p>
     */
    private <T> T inNewTransactionWithInsertRetry(Supplier<T> work) {
        try {
            return inNewTransaction(work);
        } catch (DataIntegrityViolationException concurrentInsertWon) {
            return inNewTransaction(work);
        }
    }

    private <T> T inNewTransaction(Supplier<T> work) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        // REQUIRES_NEW: the row lock must cover one counter update only, and must
        // not ride along on a caller's longer transaction (login holds one).
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template.execute(status -> work.get());
    }

    /** @return true when the key is currently in a penalty window. */
    public boolean isBlocked(String key) {
        return inNewTransactionWithInsertRetry(() -> {
            LoginRateLimit rateLimit = lockedOrNull(key);
            if (rateLimit == null) {
                return false;
            }
            if (isWindowExpired(rateLimit)) {
                loginRateLimitRepository.delete(rateLimit);
                return false;
            }
            return rateLimit.getBlockedUntil() != null
                    && LocalDateTime.now().isBefore(rateLimit.getBlockedUntil());
        });
    }

    /** Records a failed attempt and applies an exponential penalty past the threshold. */
    public void recordFailure(String key) {
        inNewTransactionWithInsertRetry(() -> {
            LoginRateLimit rateLimit = lockedOrNull(key);
            if (rateLimit == null || isWindowExpired(rateLimit)) {
                rateLimit = new LoginRateLimit(key);
                rateLimit.setAttemptCount(0);
                rateLimit.setFirstAttemptAt(LocalDateTime.now());
            }
            rateLimit.setAttemptCount(rateLimit.getAttemptCount() + 1);
            rateLimit.setLastAttemptAt(LocalDateTime.now());
            if (rateLimit.getAttemptCount() > FAILURE_THRESHOLD) {
                long penaltySeconds = (long) Math.pow(2, rateLimit.getAttemptCount() - FAILURE_THRESHOLD);
                rateLimit.setBlockedUntil(
                        LocalDateTime.now().plusSeconds(Math.min(penaltySeconds, MAX_PENALTY_SECONDS)));
            }
            loginRateLimitRepository.saveAndFlush(rateLimit);
            return null;
        });
    }

    /**
     * Fixed-cooldown throttle used for resend-style requests (recovery codes,
     * email-change codes): blocked while {@code cooldown} has not elapsed since
     * the last request, or once {@code maxPerWindow} requests happened in the
     * window.
     */
    public boolean isRequestThrottled(String key, Duration cooldown, int maxPerWindow) {
        return inNewTransactionWithInsertRetry(() -> {
            LoginRateLimit rateLimit = lockedOrNull(key);
            if (rateLimit == null || isWindowExpired(rateLimit)) {
                return false;
            }
            if (rateLimit.getLastAttemptAt() != null
                    && rateLimit.getLastAttemptAt().isAfter(LocalDateTime.now().minus(cooldown))) {
                return true;
            }
            return rateLimit.getAttemptCount() >= maxPerWindow;
        });
    }

    public void recordRequest(String key) {
        inNewTransactionWithInsertRetry(() -> {
            LoginRateLimit rateLimit = lockedOrNull(key);
            if (rateLimit == null || isWindowExpired(rateLimit)) {
                rateLimit = new LoginRateLimit(key);
                rateLimit.setAttemptCount(0);
                rateLimit.setFirstAttemptAt(LocalDateTime.now());
            }
            rateLimit.setAttemptCount(rateLimit.getAttemptCount() + 1);
            rateLimit.setLastAttemptAt(LocalDateTime.now());
            loginRateLimitRepository.saveAndFlush(rateLimit);
            return null;
        });
    }

    public void clear(String key) {
        inNewTransaction(() -> {
            loginRateLimitRepository.findWithLockByKeyIdentifier(key)
                    .ifPresent(loginRateLimitRepository::delete);
            return null;
        });
    }

    /**
     * Locked read; {@code null} when no counter exists yet. Must run inside a
     * transaction or the row lock is released the moment the statement returns.
     */
    private LoginRateLimit lockedOrNull(String key) {
        Optional<LoginRateLimit> existing = loginRateLimitRepository.findWithLockByKeyIdentifier(key);
        return existing.orElse(null);
    }
}

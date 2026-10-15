package com.roadwise.backend.service;

import com.roadwise.backend.model.LoginRateLimit;
import com.roadwise.backend.repository.LoginRateLimitRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rate limiter's counters live in the database, so concurrent attempts must
 * not lose updates.
 *
 * <p>The previous implementation used {@code synchronized} methods. That
 * serialises threads inside one JVM only — two API instances could each read
 * {@code attempt_count = 3}, each write {@code 4}, and the effective limit would
 * double. These tests drive real concurrent transactions so a regression back to
 * in-process locking (or a dropped lock) fails here rather than in production.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class RateLimitServiceConcurrencyTest {

    private static final int THREADS = 6;
    private static final int ATTEMPTS_PER_THREAD = 5;

    @Autowired
    private RateLimitService rateLimitService;

    @Autowired
    private LoginRateLimitRepository loginRateLimitRepository;

    @BeforeEach
    void setUp() {
        loginRateLimitRepository.deleteAll();
    }

    /** Fires {@code task} from many threads at once, released together. */
    private void inParallel(int threads, Callable<Void> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<Void>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    startGate.await(10, TimeUnit.SECONDS);
                    return task.call();
                }));
            }
            startGate.countDown();
            for (Future<Void> result : results) {
                result.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Every failure must be counted. A lost update here is the difference between
     * the intended lockout and one that takes twice as many guesses to trigger.
     */
    @Test
    void concurrentFailuresDoNotLoseUpdates() throws Exception {
        String key = "USER:concurrent-failures";
        rateLimitService.recordFailure(key); // seed the row so this exercises the lock path

        inParallel(THREADS, () -> {
            for (int i = 0; i < ATTEMPTS_PER_THREAD; i++) {
                rateLimitService.recordFailure(key);
            }
            return null;
        });

        LoginRateLimit stored = loginRateLimitRepository.findByKeyIdentifier(key).orElseThrow();
        assertEquals(1 + THREADS * ATTEMPTS_PER_THREAD, stored.getAttemptCount(),
                "concurrent failures lost an update — the counter is not serialised across transactions");
    }

    /**
     * The very first attempt is an INSERT against a UNIQUE column, so concurrent
     * first attempts race. One wins and the others must still be counted, via the
     * retry-in-a-new-transaction path.
     */
    @Test
    void concurrentFirstAttemptsAreAllCounted() throws Exception {
        String key = "IP:concurrent-first";

        inParallel(THREADS, () -> {
            rateLimitService.recordFailure(key);
            return null;
        });

        LoginRateLimit stored = loginRateLimitRepository.findByKeyIdentifier(key).orElseThrow();
        assertEquals(THREADS, stored.getAttemptCount(),
                "the insert race swallowed attempts instead of retrying them");
    }

    /** Once past the threshold the key reports blocked, however the count got there. */
    @Test
    void exceededThresholdBlocksTheKey() throws Exception {
        String key = "USER:blocks-after-threshold";

        inParallel(THREADS, () -> {
            rateLimitService.recordFailure(key);
            return null;
        });

        assertTrue(rateLimitService.isBlocked(key),
                "a key well past the failure threshold must report blocked");
    }

    /** Clearing is what a successful login does; it must leave nothing behind. */
    @Test
    void clearRemovesTheCounter() {
        String key = "USER:cleared-on-success";
        rateLimitService.recordFailure(key);
        rateLimitService.recordFailure(key);

        rateLimitService.clear(key);

        assertTrue(loginRateLimitRepository.findByKeyIdentifier(key).isEmpty());
    }

    /** Request throttling counts the same way under contention. */
    @Test
    void concurrentRequestsDoNotLoseUpdates() throws Exception {
        String key = "RESET_EMAIL:concurrent-requests";
        rateLimitService.recordRequest(key);

        inParallel(THREADS, () -> {
            for (int i = 0; i < ATTEMPTS_PER_THREAD; i++) {
                rateLimitService.recordRequest(key);
            }
            return null;
        });

        LoginRateLimit stored = loginRateLimitRepository.findByKeyIdentifier(key).orElseThrow();
        assertEquals(1 + THREADS * ATTEMPTS_PER_THREAD, stored.getAttemptCount(),
                "concurrent resend requests lost an update");
    }
}

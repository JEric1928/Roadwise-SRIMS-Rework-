package com.roadwise.backend.service;

import com.roadwise.backend.model.MfaChallenge;
import com.roadwise.backend.model.User;
import com.roadwise.backend.model.WebAuthnCeremony;
import com.roadwise.backend.repository.MfaChallengeRepository;
import com.roadwise.backend.repository.UserRepository;
import com.roadwise.backend.repository.WebAuthnCeremonyRepository;
import com.roadwise.backend.util.TokenHashes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The ceremony store's contract, which everything else in the passkey flow leans on.
 *
 * <p>The load-bearing property is the last test here: two concurrent requests holding
 * the same ceremony key must never both complete. Everything else (round trip, expiry,
 * supersede, outcome recording) exists to make that property meaningful.
 */
@SpringBootTest
@ActiveProfiles("test")
class WebAuthnCeremonyServiceTest {

    @Autowired
    private WebAuthnCeremonyService ceremonies;
    @Autowired
    private WebAuthnCeremonyRepository ceremonyRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private MfaChallengeRepository mfaChallengeRepository;

    private User user;
    private byte[] userHandle = {9, 9, 9, 9};

    @BeforeEach
    void setUp() {
        ceremonyRepository.deleteAll();
        mfaChallengeRepository.deleteAll();
        userRepository.deleteAll();
        User u = new User();
        u.setUsername("ceremony-user");
        u.setPassword("x");
        u.setEmail("ceremony@example.com");
        u.setRole("BARANGAY");
        u.setStatus("Active");
        user = userRepository.save(u);
    }

    @Test
    void registrationRoundTripReturnsOptionsOnceAndConsumesThem() {
        String key = ceremonies.beginRegistration(
                user.getId(), userHandle, "{\"challenge\":\"abc\"}", Duration.ofMinutes(5));

        WebAuthnCeremonyService.ClaimResult claim = ceremonies.claim(key, WebAuthnCeremony.TYPE_REGISTRATION);

        assertInstanceOf(WebAuthnCeremonyService.ClaimResult.Claimed.class, claim);
        WebAuthnCeremonyService.ClaimedCeremony claimed =
                ((WebAuthnCeremonyService.ClaimResult.Claimed) claim).ceremony();
        assertEquals("{\"challenge\":\"abc\"}", claimed.optionsJson());
        assertEquals(user.getId(), claimed.userId());
        assertArrayEquals(userHandle, claimed.userHandle());
        assertEquals(WebAuthnCeremony.TYPE_REGISTRATION, claimed.ceremonyType());

        // The payload is gone from the row: afterwards only bindings and outcome remain.
        WebAuthnCeremony row = ceremonyRepository.findById(
                TokenHashes.sha256Hex(key)).orElseThrow();
        assertEquals(WebAuthnCeremony.STATUS_CONSUMED, row.getStatus());
        assertNull(row.getOptionsJson());
    }

    @Test
    void replayedKeyIsRefusedWithoutAnyStateToChange() {
        String key = ceremonies.beginRegistration(
                user.getId(), userHandle, "{}", Duration.ofMinutes(5));
        assertInstanceOf(WebAuthnCeremonyService.ClaimResult.Claimed.class,
                ceremonies.claim(key, WebAuthnCeremony.TYPE_REGISTRATION));

        WebAuthnCeremonyService.ClaimResult replay =
                ceremonies.claim(key, WebAuthnCeremony.TYPE_REGISTRATION);

        assertEquals(new WebAuthnCeremonyService.ClaimResult.Rejected(
                        WebAuthnCeremonyService.RejectReason.ALREADY_CONSUMED), replay);
    }

    @Test
    void wrongTypeClaimIsRefusedAndLeavesCeremonyLive() {
        String key = ceremonies.beginRegistration(
                user.getId(), userHandle, "{}", Duration.ofMinutes(5));

        WebAuthnCeremonyService.ClaimResult wrong =
                ceremonies.claim(key, WebAuthnCeremony.TYPE_AUTHENTICATION);

        assertEquals(new WebAuthnCeremonyService.ClaimResult.Rejected(
                WebAuthnCeremonyService.RejectReason.WRONG_TYPE), wrong);
        WebAuthnCeremony row = ceremonyRepository.findById(
                TokenHashes.sha256Hex(key)).orElseThrow();
        assertEquals(WebAuthnCeremony.STATUS_ISSUED, row.getStatus());
    }

    @Test
    void expiredCeremonyIsRecordedAsConsumedNotSilentlyReopened() {
        String key = ceremonies.beginRegistration(
                user.getId(), userHandle, "{}", Duration.ofSeconds(-1));

        WebAuthnCeremonyService.ClaimResult expired =
                ceremonies.claim(key, WebAuthnCeremony.TYPE_REGISTRATION);

        assertEquals(new WebAuthnCeremonyService.ClaimResult.Rejected(
                WebAuthnCeremonyService.RejectReason.EXPIRED), expired);
        WebAuthnCeremony row = ceremonyRepository.findById(
                TokenHashes.sha256Hex(key)).orElseThrow();
        assertEquals(WebAuthnCeremony.STATUS_CONSUMED, row.getStatus());
        assertEquals("EXPIRED", row.getOutcome());
    }

    @Test
    void unknownKeyIsRefused() {
        assertEquals(new WebAuthnCeremonyService.ClaimResult.Rejected(
                        WebAuthnCeremonyService.RejectReason.NOT_FOUND),
                ceremonies.claim("no-such-key", WebAuthnCeremony.TYPE_REGISTRATION));
    }

    @Test
    void reissuingSupersedesTheAbandonedCeremony() {
        String first = ceremonies.beginRegistration(
                user.getId(), userHandle, "{}", Duration.ofMinutes(5));
        String second = ceremonies.beginRegistration(
                user.getId(), userHandle, "{}", Duration.ofMinutes(5));

        assertNotEquals(first, second);
        WebAuthnCeremony firstRow = ceremonyRepository.findById(
                TokenHashes.sha256Hex(first)).orElseThrow();
        assertEquals("SUPERSEDED", firstRow.getOutcome());
        assertEquals(WebAuthnCeremony.STATUS_CONSUMED, firstRow.getStatus());
        assertInstanceOf(WebAuthnCeremonyService.ClaimResult.Rejected.class,
                ceremonies.claim(first, WebAuthnCeremony.TYPE_REGISTRATION));
        assertInstanceOf(WebAuthnCeremonyService.ClaimResult.Claimed.class,
                ceremonies.claim(second, WebAuthnCeremony.TYPE_REGISTRATION));
    }

    @Test
    void assertionCeremonyIsBoundToItsLoginChallenge() {
        MfaChallenge challenge = newChallenge();
        String key = ceremonies.beginAuthentication(
                challenge.getId(), "{\"rpId\":\"localhost\"}", Duration.ofMinutes(5));

        WebAuthnCeremonyService.ClaimResult claim =
                ceremonies.claim(key, WebAuthnCeremony.TYPE_AUTHENTICATION);

        WebAuthnCeremonyService.ClaimedCeremony claimed =
                ((WebAuthnCeremonyService.ClaimResult.Claimed) claim).ceremony();
        assertEquals(challenge.getId(), claimed.mfaChallengeId());
        assertNull(claimed.userId());
    }

    @Test
    void completeRecordsTheFinalOutcomeOnAConsumedRow() {
        String key = ceremonies.beginRegistration(
                user.getId(), userHandle, "{}", Duration.ofMinutes(5));
        ceremonies.claim(key, WebAuthnCeremony.TYPE_REGISTRATION);

        ceremonies.complete(key, "FAILED_VERIFICATION");

        WebAuthnCeremony row = ceremonyRepository.findById(
                TokenHashes.sha256Hex(key)).orElseThrow();
        assertEquals("FAILED_VERIFICATION", row.getOutcome());
        assertEquals(WebAuthnCeremony.STATUS_CONSUMED, row.getStatus());
    }

    @Test
    void rowsPastTheAuditWindowArePurgedOnTheNextBegin() {
        // One row far past the 72h retention, one recent: only the old one goes.
        WebAuthnCeremony ancient = new WebAuthnCeremony();
        ancient.setCeremonyKeyHash("ancient-hash");
        ancient.setCeremonyType(WebAuthnCeremony.TYPE_REGISTRATION);
        ancient.setUserId(user.getId());
        ancient.setStatus(WebAuthnCeremony.STATUS_CONSUMED);
        ancient.setExpiresAt(LocalDateTime.now().minusHours(80));
        ceremonyRepository.save(ancient);

        WebAuthnCeremony recent = new WebAuthnCeremony();
        recent.setCeremonyKeyHash("recent-hash");
        recent.setCeremonyType(WebAuthnCeremony.TYPE_REGISTRATION);
        recent.setUserId(user.getId());
        recent.setStatus(WebAuthnCeremony.STATUS_CONSUMED);
        recent.setExpiresAt(LocalDateTime.now().minusHours(1));
        ceremonyRepository.save(recent);

        ceremonies.beginRegistration(user.getId(), userHandle, "{}", Duration.ofMinutes(5));

        assertFalse(ceremonyRepository.existsById("ancient-hash"));
        assertTrue(ceremonyRepository.existsById("recent-hash"));
    }

    /**
     * The property the whole design turns on: a one-time ceremony stays one-time under
     * concurrency. Both threads race to claim; exactly one may win, ever.
     */
    @Test
    void concurrentClaimsProduceExactlyOneWinner() throws Exception {
        String key = ceremonies.beginRegistration(
                user.getId(), userHandle, "{}", Duration.ofMinutes(5));
        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<WebAuthnCeremonyService.ClaimResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                // Lock contention can surface as an exception on some dialects; a lost
                // race must never become a second win, so retries are allowed but every
                // result is still counted.
                for (int attempt = 0; attempt < 5; attempt++) {
                    try {
                        WebAuthnCeremonyService.ClaimResult result =
                                ceremonies.claim(key, WebAuthnCeremony.TYPE_REGISTRATION);
                        if (result instanceof WebAuthnCeremonyService.ClaimResult.Claimed) {
                            return result;
                        }
                    } catch (RuntimeException contention) {
                        // retry
                    }
                }
                return new WebAuthnCeremonyService.ClaimResult.Rejected(
                        WebAuthnCeremonyService.RejectReason.ALREADY_CONSUMED);
            }));
        }
        start.countDown();
        long winners = 0;
        for (Future<WebAuthnCeremonyService.ClaimResult> future : futures) {
            if (future.get() instanceof WebAuthnCeremonyService.ClaimResult.Claimed) {
                winners++;
            }
        }
        pool.shutdownNow();
        assertEquals(1, winners, "exactly one concurrent claim may win a ceremony");
    }

    private MfaChallenge newChallenge() {
        MfaChallenge challenge = MfaChallenge.forToken(
                user, "token-hash-" + System.nanoTime(), LocalDateTime.now().plusMinutes(10));
        challenge.setFactorType("WEBAUTHN");
        return mfaChallengeRepository.save(challenge);
    }
}
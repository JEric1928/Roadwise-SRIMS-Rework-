package com.roadwise.backend.service;

import com.roadwise.backend.model.WebAuthnCeremony;
import com.roadwise.backend.repository.WebAuthnCeremonyRepository;
import com.roadwise.backend.util.TokenHashes;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Durable, explicitly-keyed store for in-flight WebAuthn ceremonies.
 *
 * <p>This exists because the module's own options repositories are session-bound and
 * this application has no session. Nothing here knows the WebAuthn protocol: it stores
 * opaque options JSON produced elsewhere (by the relying-party operations, serialised
 * with the Jackson module), and hands it back exactly once.
 *
 * <p>The consumption contract matters more than the storage, so it is stated plainly:
 * {@link #claim} transitions the row to CONSUMED under a row lock in the caller's
 * transaction, and the caller verifies the response in that same transaction. A failed
 * verification must be converted into a committed {@code FAILED_*} outcome, not thrown
 * -- Spring rolls back on runtime exceptions, and a rollback would un-consume the row
 * and re-open the ceremony. Only genuinely unexpected errors should propagate.
 */
@Service
public class WebAuthnCeremonyService {

    /** How long a consumed row is retained before {@link #purgeAgedRows} removes it. */
    static final Duration AUDIT_RETENTION = Duration.ofHours(72);

    private final WebAuthnCeremonyRepository ceremonies;
    private final SecureRandom secureRandom = new SecureRandom();

    public WebAuthnCeremonyService(WebAuthnCeremonyRepository ceremonies) {
        this.ceremonies = ceremonies;
    }

    public enum RejectReason {
        NOT_FOUND,
        EXPIRED,
        ALREADY_CONSUMED,
        WRONG_TYPE
    }

    public sealed interface ClaimResult permits ClaimResult.Claimed, ClaimResult.Rejected {
        record Claimed(ClaimedCeremony ceremony) implements ClaimResult {
        }

        record Rejected(RejectReason reason) implements ClaimResult {
        }
    }

    /**
     * What a successful claim returns: the original options plus the row's ownership
     * bindings, so the caller can verify that the response belongs to the right user
     * or the right login challenge.
     */
    public record ClaimedCeremony(
            String optionsJson,
            Long userId,
            byte[] userHandle,
            Long mfaChallengeId,
            String ceremonyType) {
    }

    /**
     * Opens a registration ceremony for an authenticated user.
     *
     * @return the raw ceremony key, shown to the client exactly once
     */
    @Transactional
    public String beginRegistration(Long userId, byte[] userHandle, String optionsJson, Duration ttl) {
        purgeAgedRows();
        // One live registration ceremony per user. Starting a new enrolment supersedes
        // an abandoned one rather than leaving two valid challenges for the account.
        supersedeLive(userId, null);
        return store(WebAuthnCeremony.TYPE_REGISTRATION, userId, userHandle, null, optionsJson, ttl);
    }

    /**
     * Opens an assertion ceremony bound to a pre-authentication MFA challenge.
     *
     * @return the raw ceremony key, shown to the client exactly once
     */
    @Transactional
    public String beginAuthentication(Long mfaChallengeId, String optionsJson, Duration ttl) {
        purgeAgedRows();
        supersedeLive(null, mfaChallengeId);
        return store(WebAuthnCeremony.TYPE_AUTHENTICATION, null, null, mfaChallengeId, optionsJson, ttl);
    }

    /**
     * Atomically claims a ceremony: locks the row, checks it is a live ceremony of the
     * expected type, marks it CONSUMED, and clears the options payload while returning
     * a copy of it.
     *
     * <p>An expired row is marked CONSUMED with outcome {@code EXPIRED} so the expiry is
     * recorded rather than silently re-checkable. A replayed key is refused without any
     * state change -- there is nothing left to change, which is the point.
     */
    @Transactional
    public ClaimResult claim(String rawCeremonyKey, String expectedType) {
        Optional<WebAuthnCeremony> found =
                ceremonies.findByCeremonyKeyHash(TokenHashes.sha256Hex(rawCeremonyKey));
        if (found.isEmpty()) {
            return new ClaimResult.Rejected(RejectReason.NOT_FOUND);
        }

        WebAuthnCeremony row = found.get();
        if (!expectedType.equals(row.getCeremonyType())) {
            return new ClaimResult.Rejected(RejectReason.WRONG_TYPE);
        }
        if (!WebAuthnCeremony.STATUS_ISSUED.equals(row.getStatus())) {
            return new ClaimResult.Rejected(RejectReason.ALREADY_CONSUMED);
        }
        if (!row.isLive()) {
            row.setStatus(WebAuthnCeremony.STATUS_CONSUMED);
            row.setOutcome("EXPIRED");
            row.setConsumedAt(LocalDateTime.now());
            row.setOptionsJson(null);
            ceremonies.save(row);
            return new ClaimResult.Rejected(RejectReason.EXPIRED);
        }

        String options = row.getOptionsJson();
        row.setStatus(WebAuthnCeremony.STATUS_CONSUMED);
        row.setOutcome("CLAIMED");
        row.setConsumedAt(LocalDateTime.now());
        row.setOptionsJson(null);
        ceremonies.save(row);
        return new ClaimResult.Claimed(
                new ClaimedCeremony(options, row.getUserId(), row.getUserHandle(),
                        row.getMfaChallengeId(), row.getCeremonyType()));
    }

    /**
     * Records the final outcome of a claimed ceremony in the same transaction as the
     * verification -- {@code COMPLETED} on success, {@code FAILED_*} on a bad response.
     * Both must commit; see the class contract about not throwing on expected failures.
     */
    @Transactional
    public void complete(String rawCeremonyKey, String outcome) {
        ceremonies.findByCeremonyKeyHash(TokenHashes.sha256Hex(rawCeremonyKey))
                .ifPresent(row -> {
                    row.setOutcome(outcome);
                    ceremonies.save(row);
                });
    }

    private String store(String type, Long userId, byte[] userHandle, Long challengeId,
                         String optionsJson, Duration ttl) {
        byte[] raw = new byte[32];
        secureRandom.nextBytes(raw);
        String key = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

        WebAuthnCeremony row = new WebAuthnCeremony();
        row.setCeremonyKeyHash(TokenHashes.sha256Hex(key));
        row.setCeremonyType(type);
        row.setUserId(userId);
        row.setUserHandle(userHandle);
        row.setMfaChallengeId(challengeId);
        row.setOptionsJson(optionsJson);
        row.setExpiresAt(LocalDateTime.now().plus(ttl));
        ceremonies.save(row);
        return key;
    }

    private void supersedeLive(Long userId, Long challengeId) {
        List<WebAuthnCeremony> live = userId != null
                ? ceremonies.findByUserIdAndCeremonyTypeAndStatus(
                        userId, WebAuthnCeremony.TYPE_REGISTRATION, WebAuthnCeremony.STATUS_ISSUED)
                : ceremonies.findByMfaChallengeIdAndCeremonyTypeAndStatus(
                        challengeId, WebAuthnCeremony.TYPE_AUTHENTICATION, WebAuthnCeremony.STATUS_ISSUED);
        LocalDateTime now = LocalDateTime.now();
        for (WebAuthnCeremony stale : live) {
            stale.setStatus(WebAuthnCeremony.STATUS_CONSUMED);
            stale.setOutcome("SUPERSEDED");
            stale.setConsumedAt(now);
            stale.setOptionsJson(null);
            ceremonies.save(stale);
        }
    }

    private void purgeAgedRows() {
        ceremonies.purgeExpiredBefore(LocalDateTime.now().minus(AUDIT_RETENTION));
    }
}

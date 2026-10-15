package com.roadwise.backend.repository;

import com.roadwise.backend.model.WebAuthnCeremony;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface WebAuthnCeremonyRepository extends JpaRepository<WebAuthnCeremony, String> {

    /**
     * Locking read for atomic claim/consume.
     *
     * <p>The lock is the whole point: two concurrent requests presenting the same
     * ceremony key serialise here, so the second observes the first's CONSUMED marker
     * instead of racing it. The lock is held for the caller's transaction, which is why
     * claiming and verifying must happen in one transaction -- the verification result
     * commits together with the consumption, and a failed verification rolls both back.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Transactional
    Optional<WebAuthnCeremony> findByCeremonyKeyHash(String ceremonyKeyHash);

    /** Live registration ceremonies for a user, superseded when a new one begins. */
    List<WebAuthnCeremony> findByUserIdAndCeremonyTypeAndStatus(
            Long userId, String ceremonyType, String status);

    /** Live assertion ceremonies for a login challenge. */
    List<WebAuthnCeremony> findByMfaChallengeIdAndCeremonyTypeAndStatus(
            Long mfaChallengeId, String ceremonyType, String status);

    /**
     * Purges rows past the audit window: consumed rows whose expiry is old, and
     * abandoned ISSUED rows that were never completed.
     */
    @Modifying
    @Transactional
    @Query("delete from WebAuthnCeremony c where c.expiresAt < :cutoff")
    int purgeExpiredBefore(@Param("cutoff") LocalDateTime cutoff);
}

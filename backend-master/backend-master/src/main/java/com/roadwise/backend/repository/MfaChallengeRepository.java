package com.roadwise.backend.repository;

import com.roadwise.backend.model.MfaChallenge;
import com.roadwise.backend.model.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface MfaChallengeRepository extends JpaRepository<MfaChallenge, Long> {

    /**
     * Serializes concurrent verification attempts for the same challenge so a
     * code cannot be consumed twice and attempt counts cannot race.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Transactional
    Optional<MfaChallenge> findTopByUserAndUsedFalseOrderByCreatedAtDesc(User user);

    /**
     * Resolves the challenge belonging to a server-issued token.
     *
     * <p>This is the replacement for driving verification off a client-supplied user
     * id. Note the distinction from the method above: that one answers "does this
     * account have a recent challenge?" and is only fit for the resend cooldown.
     * It must never be used to authorise a verification, because it cannot tell this
     * caller's login attempt apart from anybody else's.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Transactional
    Optional<MfaChallenge> findByChallengeTokenHash(String challengeTokenHash);

    /** Used to invalidate every outstanding challenge on credential changes. */
    List<MfaChallenge> findByUserAndUsedFalse(User user);
}

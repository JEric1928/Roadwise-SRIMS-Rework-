package com.roadwise.backend.repository;

import com.roadwise.backend.model.MfaChallenge;
import com.roadwise.backend.model.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

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
}

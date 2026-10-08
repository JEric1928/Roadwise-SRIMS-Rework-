package com.roadwise.backend.repository;

import com.roadwise.backend.model.MfaChallenge;
import com.roadwise.backend.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface MfaChallengeRepository extends JpaRepository<MfaChallenge, Long> {
    Optional<MfaChallenge> findTopByUserAndUsedFalseOrderByCreatedAtDesc(User user);
}

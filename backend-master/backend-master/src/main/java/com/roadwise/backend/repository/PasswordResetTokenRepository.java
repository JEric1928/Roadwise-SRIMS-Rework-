package com.roadwise.backend.repository;

import com.roadwise.backend.model.PasswordResetToken;
import com.roadwise.backend.model.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {
    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    /** Pessimistic lock so a reset code can only be consumed once. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Transactional
    Optional<PasswordResetToken> findTopByUserAndUsedFalseOrderByCreatedAtDesc(User user);

    /** Used to invalidate every outstanding code on credential changes. */
    List<PasswordResetToken> findByUserAndUsedFalse(User user);
}

package com.roadwise.backend.repository;

import com.roadwise.backend.model.RefreshToken;
import com.roadwise.backend.model.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** Pessimistic lock so concurrent refresh rotations cannot both succeed. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RefreshToken> findWithLockByTokenHash(String tokenHash);

    List<RefreshToken> findByUserAndRevokedFalse(User user);
}

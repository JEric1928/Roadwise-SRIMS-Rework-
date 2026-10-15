package com.roadwise.backend.repository;

import com.roadwise.backend.model.MfaRecoveryCode;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface MfaRecoveryCodeRepository extends JpaRepository<MfaRecoveryCode, Long> {

    long countByUserIdAndUsedAtIsNull(Long userId);

    /**
     * Locking read of the unused set.
     *
     * <p>Codes are BCrypt hashes, so a submitted code has to be compared against
     * each candidate rather than looked up by hash. The lock makes redeem-and-mark
     * atomic, so two requests carrying the same code cannot both succeed.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Transactional
    @Query("select c from MfaRecoveryCode c where c.user.id = :userId and c.usedAt is null")
    List<MfaRecoveryCode> findUnusedForUpdate(@Param("userId") Long userId);

    /** Re-issuing replaces the set, so superseded codes must not linger. */
    @Modifying
    @Transactional
    @Query("delete from MfaRecoveryCode c where c.user.id = :userId")
    int deleteByUserId(@Param("userId") Long userId);
}

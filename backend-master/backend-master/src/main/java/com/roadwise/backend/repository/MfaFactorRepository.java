package com.roadwise.backend.repository;

import com.roadwise.backend.model.MfaFactor;
import com.roadwise.backend.model.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface MfaFactorRepository extends JpaRepository<MfaFactor, Long> {

    List<MfaFactor> findByUserIdAndStatusOrderByCreatedAtAsc(Long userId, String status);

    /** Everything the user has ever enrolled, newest first, including disabled rows. */
    List<MfaFactor> findByUserIdOrderByCreatedAtDesc(Long userId);

    Optional<MfaFactor> findFirstByUserIdAndFactorTypeAndStatus(
            Long userId, String factorType, String status);

    Optional<MfaFactor> findFirstByUserIdAndFactorTypeAndStatusNotOrderByCreatedAtDesc(
            Long userId, String factorType, String status);

    /**
     * Locking read scoped to the owning user.
     *
     * <p>The user scoping is the security-relevant part: a factor id arriving from a
     * request must never resolve to a row owned by somebody else. The row lock
     * serialises concurrent verifications so a TOTP code cannot be accepted twice
     * through a race on {@code last_totp_step}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Transactional
    @Query("select f from MfaFactor f where f.id = :id and f.user.id = :userId")
    Optional<MfaFactor> findByIdAndUserIdForUpdate(@Param("id") Long id, @Param("userId") Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Transactional
    @Query("select f from MfaFactor f where f.user = :user and f.factorType = :factorType and f.status = :status")
    Optional<MfaFactor> findForUpdate(
            @Param("user") User user,
            @Param("factorType") String factorType,
            @Param("status") String status);

    long countByUserIdAndStatusNot(Long userId, String status);

    // --- Passkey (WebAuthn) lookups ---

    Optional<MfaFactor> findByCredentialId(byte[] credentialId);

    /** Assertion lookup: a disabled passkey must never satisfy an authentication. */
    Optional<MfaFactor> findByCredentialIdAndStatus(byte[] credentialId, String status);

    List<MfaFactor> findByUserIdAndFactorType(Long userId, String factorType);

    List<MfaFactor> findByUserIdAndFactorTypeAndStatus(Long userId, String factorType, String status);
}

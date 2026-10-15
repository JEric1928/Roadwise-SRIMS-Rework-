package com.roadwise.backend.repository;

import com.roadwise.backend.model.LoginRateLimit;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface LoginRateLimitRepository extends JpaRepository<LoginRateLimit, Long> {

    Optional<LoginRateLimit> findByKeyIdentifier(String keyIdentifier);

    /**
     * Row-locking read for the read-modify-write paths in {@link
     * com.roadwise.backend.service.RateLimitService}.
     *
     * <p>{@code SELECT ... FOR UPDATE} is what actually serialises concurrent
     * attempts: the counters live in the database, so an in-process lock cannot
     * coordinate two API instances. Must be called inside a transaction or the
     * lock is released immediately.</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM LoginRateLimit r WHERE r.keyIdentifier = :keyIdentifier")
    Optional<LoginRateLimit> findWithLockByKeyIdentifier(@Param("keyIdentifier") String keyIdentifier);
}

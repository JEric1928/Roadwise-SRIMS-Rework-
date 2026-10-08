package com.roadwise.backend.repository;

import com.roadwise.backend.model.LoginRateLimit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface LoginRateLimitRepository extends JpaRepository<LoginRateLimit, Long> {
    Optional<LoginRateLimit> findByKeyIdentifier(String keyIdentifier);
}

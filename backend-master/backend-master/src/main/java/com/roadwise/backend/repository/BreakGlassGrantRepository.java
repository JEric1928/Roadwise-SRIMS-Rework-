package com.roadwise.backend.repository;

import com.roadwise.backend.model.BreakGlassGrant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface BreakGlassGrantRepository extends JpaRepository<BreakGlassGrant, Long> {

    /** The query on the authorization path: is there a live grant for this account right now? */
    Optional<BreakGlassGrant> findFirstByGrantedToAndRevokedAtIsNullAndExpiresAtAfterOrderByExpiresAtDesc(
            Long grantedTo, LocalDateTime now);

    List<BreakGlassGrant> findTop50ByOrderByGrantedAtDesc();
}

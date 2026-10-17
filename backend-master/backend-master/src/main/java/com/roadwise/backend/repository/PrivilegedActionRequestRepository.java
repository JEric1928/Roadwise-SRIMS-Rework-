package com.roadwise.backend.repository;

import com.roadwise.backend.model.PrivilegedActionRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Status transitions are conditional updates, not read-then-write.
 *
 * <p>Two approvers opening the same request, or a maker redeeming an approval twice, is not a
 * hypothetical: it is what a double click or a retried request produces. {@code ... and
 * status = ...} in the WHERE clause makes the database the arbiter, so exactly one of the
 * racing calls changes a row and the others get zero.
 */
@Repository
public interface PrivilegedActionRequestRepository extends JpaRepository<PrivilegedActionRequest, Long> {

    List<PrivilegedActionRequest> findByStatusOrderByRequestedAtDesc(String status);

    List<PrivilegedActionRequest> findByRequestedByOrderByRequestedAtDesc(Long requestedBy);

    List<PrivilegedActionRequest> findByPermissionKeyAndTargetUserIdAndRequestedByAndStatus(
            String permissionKey, Long targetUserId, Long requestedBy, String status);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update PrivilegedActionRequest r
               set r.status = :status,
                   r.decidedBy = :decidedBy,
                   r.decidedAt = CURRENT_TIMESTAMP,
                   r.decisionNote = :note
             where r.id = :id and r.status = 'PENDING'
            """)
    int decide(@Param("id") Long id,
               @Param("status") String status,
               @Param("decidedBy") Long decidedBy,
               @Param("note") String note);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update PrivilegedActionRequest r
               set r.status = 'EXECUTED',
                   r.executedAt = CURRENT_TIMESTAMP
             where r.id = :id and r.status = 'APPROVED'
            """)
    int markExecuted(@Param("id") Long id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update PrivilegedActionRequest r
               set r.status = 'DENIED',
                   r.decisionNote = :note
             where r.id = :id and r.status = 'PENDING'
            """)
    int expire(@Param("id") Long id, @Param("note") String note);
}

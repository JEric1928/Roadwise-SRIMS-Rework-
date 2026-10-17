package com.roadwise.backend.repository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Writes one row per authorization decision worth keeping.
 *
 * <p>Recorded: every {@code canActOn} outcome (allow or deny) and every denied permission
 * check. Not recorded: allowed routine reads -- {@code @PreAuthorize("@authz.has('user:read')")}
 * runs on every authenticated request, and a write behind each of them would cost more than
 * the evidence is worth.
 *
 * <p>Insert failures are swallowed after a warning. An audit write must never be the reason a
 * legitimate request fails, and the alternative -- refusing the action because the log is
 * unavailable -- turns a logging problem into an outage.
 */
@Repository
public class AuthzDecisionRepository {

    private static final Logger log = LoggerFactory.getLogger(AuthzDecisionRepository.class);

    private static final String INSERT = """
            INSERT INTO authz_decision_log
                (actor_id, permission_key, target_user_id, decision, reason,
                 http_method, request_path, correlation_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;

    public AuthzDecisionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void record(Long actorId, String permission, Long targetUserId, String decision,
                       String reason, String httpMethod, String requestPath, String correlationId) {
        try {
            jdbcTemplate.update(INSERT, actorId, permission, targetUserId, decision, reason,
                    httpMethod, requestPath, correlationId);
        } catch (RuntimeException ex) {
            log.warn("could not record authorization decision ({} {} on {}): {}",
                    decision, permission, targetUserId, ex.toString());
        }
    }
}

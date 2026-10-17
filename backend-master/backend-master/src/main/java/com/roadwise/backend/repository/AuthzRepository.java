package com.roadwise.backend.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Reads the authorization policy introduced by V21.
 *
 * <p>Both queries join through {@code authz_role_alias} rather than comparing role strings
 * directly, so a permission check cannot disagree with the role text about what a caller
 * is. A caller whose role has no alias resolves to no permissions and no rank -- fail
 * closed, which is the only safe direction for an unknown role.
 *
 * <p>Two small queries rather than a cache: they run only where an administrative action
 * is attempted, and a cache here would be one more thing that can disagree with the
 * database after a grant is revoked.
 */
@Repository
public class AuthzRepository {

    private static final String PERMISSIONS_FOR_USER = """
            SELECT DISTINCT rp.permission_key
              FROM users u
              JOIN authz_role_alias ra   ON ra.role_text = lower(u.role)
              JOIN authz_role_permission rp ON rp.role_key = ra.role_key
             WHERE u.id = ?
            """;

    private static final String RANK_FOR_USER = """
            SELECT r.rank_order
              FROM users u
              JOIN authz_role_alias ra ON ra.role_text = lower(u.role)
              JOIN authz_role r        ON r.role_key = ra.role_key
             WHERE u.id = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public AuthzRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<String> permissionsFor(long userId) {
        return jdbcTemplate.queryForList(PERMISSIONS_FOR_USER, String.class, userId);
    }

    /** @return the caller's rank, or {@code null} when their role has no policy alias. */
    public Integer rankOf(long userId) {
        List<Integer> ranks = jdbcTemplate.queryForList(RANK_FOR_USER, Integer.class, userId);
        return ranks.isEmpty() ? null : ranks.get(0);
    }
}

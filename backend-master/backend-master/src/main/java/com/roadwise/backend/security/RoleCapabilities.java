package com.roadwise.backend.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Single source of truth for role → capability resolution.
 *
 * <p>Registered as the bean {@code roleCapabilities} so method security can call it
 * from expression strings:
 * {@code @PreAuthorize("@roleCapabilities.technicalSurvey(authentication)")}. Those
 * methods exist for SpEL ergonomics; {@link #has} and {@link #resolve} are the real
 * API and the only two places the mapping is read.</p>
 *
 * <p>Role matching is deliberately tolerant. {@code JwtAuthenticationFilter} and
 * {@code SessionAuthenticator} disagree about the {@code ROLE_} prefix — one strips
 * it, the other adds it — so a capability check that did a plain string compare would
 * behave differently depending on how the request authenticated. Labels are also
 * compared case-insensitively and with whitespace collapsed, because
 * {@code "CPDO Admin"} and {@code "CPDO  ADMIN"} both occur in seed data.</p>
 */
@Component("roleCapabilities")
public class RoleCapabilities {

    /**
     * Conservative by design: a label absent from this map has no capabilities.
     *
     * <p>{@code ADMIN} and {@code CPDO Admin} mirror each other because the existing
     * controllers already treat them as interchangeable
     * ({@code hasAnyRole('ADMIN', 'CPDO Admin')}). Narrowing that during a schema
     * refactor would lock administrators out of dashboards they use today, so the
     * split, if wanted, belongs in its own change.</p>
     */
    private static final Map<String, Set<RoleCapability>> BY_ROLE = new HashMap<>();

    static {
        BY_ROLE.put("BARANGAY", EnumSet.of(
                RoleCapability.FIELD_REPORTING));

        BY_ROLE.put("CPDO ADMIN", EnumSet.of(
                RoleCapability.TECHNICAL_SURVEY,
                RoleCapability.VALIDATION,
                RoleCapability.PRIORITY_RANKING,
                RoleCapability.LGU_REPORTING,
                RoleCapability.OPERATIONAL_STATUS,
                RoleCapability.PROOF_OF_REPAIR));

        BY_ROLE.put("ADMIN", EnumSet.of(
                RoleCapability.TECHNICAL_SURVEY,
                RoleCapability.VALIDATION,
                RoleCapability.PRIORITY_RANKING,
                RoleCapability.LGU_REPORTING,
                RoleCapability.OPERATIONAL_STATUS,
                RoleCapability.PROOF_OF_REPAIR));

        // "CEO field engineers" in the spec: they run the survey, and they own the
        // operational lifecycle and the proof-of-repair evidence. They do not rank or
        // validate — that stays with CPDO.
        BY_ROLE.put("ENGINEER", EnumSet.of(
                RoleCapability.TECHNICAL_SURVEY,
                RoleCapability.OPERATIONAL_STATUS,
                RoleCapability.PROOF_OF_REPAIR));

        BY_ROLE.put("CITY ENGINEER", EnumSet.of(
                RoleCapability.TECHNICAL_SURVEY,
                RoleCapability.OPERATIONAL_STATUS,
                RoleCapability.PROOF_OF_REPAIR));
    }

    /** Canonical map key for a raw role label. Returns "" for null/blank input. */
    static String normalize(String role) {
        if (role == null) {
            return "";
        }
        String trimmed = role.trim();
        if (trimmed.regionMatches(true, 0, "ROLE_", 0, 5)) {
            trimmed = trimmed.substring(5);
        }
        return trimmed.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    public Set<RoleCapability> resolve(String role) {
        Set<RoleCapability> capabilities = BY_ROLE.get(normalize(role));
        return capabilities == null ? EnumSet.noneOf(RoleCapability.class) : Collections.unmodifiableSet(capabilities);
    }

    public boolean has(String role, RoleCapability capability) {
        return capability != null && resolve(role).contains(capability);
    }

    /** True if any authority granted on the authenticated principal carries the capability. */
    public boolean has(Authentication authentication, RoleCapability capability) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if (has(authority.getAuthority(), capability)) {
                return true;
            }
        }
        return false;
    }

    // --- SpEL entry points; one per capability so @PreAuthorize stays readable ---

    public boolean fieldReporting(Authentication authentication) {
        return has(authentication, RoleCapability.FIELD_REPORTING);
    }

    public boolean technicalSurvey(Authentication authentication) {
        return has(authentication, RoleCapability.TECHNICAL_SURVEY);
    }

    public boolean validation(Authentication authentication) {
        return has(authentication, RoleCapability.VALIDATION);
    }

    public boolean priorityRanking(Authentication authentication) {
        return has(authentication, RoleCapability.PRIORITY_RANKING);
    }

    public boolean lguReporting(Authentication authentication) {
        return has(authentication, RoleCapability.LGU_REPORTING);
    }

    public boolean operationalStatus(Authentication authentication) {
        return has(authentication, RoleCapability.OPERATIONAL_STATUS);
    }

    public boolean proofOfRepair(Authentication authentication) {
        return has(authentication, RoleCapability.PROOF_OF_REPAIR);
    }
}

package com.roadwise.backend.security;

/**
 * What a caller is allowed to *do*, as opposed to which group they belong to.
 *
 * <p>The platform's access rules are described in the business spec as three tiers
 * (barangay, CPDO, CEO), while the running system stores five role labels
 * ({@code ADMIN}, {@code CPDO Admin}, {@code BARANGAY}, {@code ENGINEER},
 * {@code City Engineer}) in {@code users.role}. Mapping role → capability in one
 * place means the tiers can be expressed without a role rename migration, and a
 * sixth label later only needs an entry in {@link RoleCapabilities} rather than an
 * audit of every {@code @PreAuthorize} string.</p>
 */
public enum RoleCapability {

    /** ROLE_BARANGAY: file reports, read own submission status. */
    FIELD_REPORTING,

    /** ROLE_CPDO / engineer: append the technical survey after the fact. */
    TECHNICAL_SURVEY,

    /** ROLE_CPDO: accept or reject a field report. */
    VALIDATION,

    /** ROLE_CPDO: recompute the priority masterlist. */
    PRIORITY_RANKING,

    /** ROLE_CPDO: generate LGU-facing reports. */
    LGU_REPORTING,

    /** ROLE_CEO: move a validated project through PENDING → ONGOING → COMPLETED. */
    OPERATIONAL_STATUS,

    /** ROLE_CEO: attach proof-of-repair evidence. */
    PROOF_OF_REPAIR
}

package com.roadwise.backend.security;

import com.roadwise.backend.dto.ReportCreateDto;

/**
 * Server-side enforcement of the field-report / technical-survey split (Module 2).
 *
 * <p>The barangay form is meant to capture <em>what</em> and <em>where</em>. The
 * measured geometry — length, width, depth — belongs to the CPDO survey, because it
 * is the evidence a repair estimate and a priority tier are built from, and because
 * an inspector without a tape measure should not be able to guess at it.</p>
 *
 * <p>Most of that boundary is already enforced structurally, not here:</p>
 * <ul>
 *   <li>{@code damageDepth}, {@code surfaceType}, {@code cpdoRepairPlan} and
 *       {@code surveyedBy} do not exist on {@link ReportCreateDto} at all, so the
 *       legacy create endpoint cannot bind them.</li>
 *   <li>The 3-field endpoint takes {@code BarangayReportCreateDto}, which has no
 *       engineering fields either.</li>
 * </ul>
 *
 * <p>What remains is the legacy endpoint's inherited {@code damageLength} /
 * {@code damageWidth} parameters. A caller holding {@code TECHNICAL_SURVEY} keeps
 * them — that is how a CPDO administrator can file on an officer's behalf. Everyone
 * else has them discarded rather than rejected: the request is still a valid field
 * report, and failing the whole submission over two extra form parameters would lose
 * the photos and the pin along with them.</p>
 */
public final class FieldScopePolicy {

    private FieldScopePolicy() {
        // Static policy holder.
    }

    /**
     * Discard the damage measurements a caller is not entitled to record.
     *
     * <p>Mutates and returns the same instance so call sites read as a single
     * statement. Safe on a null DTO.</p>
     *
     * @param dto        the bound request, may be null
     * @param canSurvey  true when the caller holds {@link RoleCapability#TECHNICAL_SURVEY}
     * @return the same DTO, with engineering fields cleared when not entitled
     */
    public static ReportCreateDto applySurveyScope(ReportCreateDto dto, boolean canSurvey) {
        if (dto == null || canSurvey) {
            return dto;
        }
        dto.setDamageLength(null);
        dto.setDamageWidth(null);
        return dto;
    }

    /** True when the role is permitted to record engineering measurements. */
    public static boolean canSurvey(String role, RoleCapabilities capabilities) {
        return capabilities != null && capabilities.has(role, RoleCapability.TECHNICAL_SURVEY);
    }
}

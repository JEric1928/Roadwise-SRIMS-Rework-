package com.roadwise.backend.security;

import com.roadwise.backend.dto.ReportCreateDto;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The role split for engineering measurements, exercised without Spring.
 *
 * <p>The dangerous failure here is silent data loss in the other direction: a CPDO
 * administrator filing on an officer's behalf must not have their measurements
 * stripped. Both directions are asserted.</p>
 */
class FieldScopePolicyTest {

    private final RoleCapabilities capabilities = new RoleCapabilities();

    private static ReportCreateDto dtoWithMeasurements() {
        ReportCreateDto dto = new ReportCreateDto();
        dto.setDamageType("Pothole");
        dto.setDamageDescription("near the school gate");
        dto.setDamageLength(2.5);
        dto.setDamageWidth(1.2);
        return dto;
    }

    @Test
    void barangayCallerLosesMeasurementsButKeepsTheReport() {
        ReportCreateDto dto = FieldScopePolicy.applySurveyScope(dtoWithMeasurements(), false);

        assertNull(dto.getDamageLength(), "a barangay inspector may not record length");
        assertNull(dto.getDamageWidth(), "a barangay inspector may not record width");
        // The rest of the submission must survive: discarding the report over two
        // extra form parameters would lose the photos and the GPS pin too.
        assertEquals("Pothole", dto.getDamageType());
        assertEquals("near the school gate", dto.getDamageDescription());
    }

    @Test
    void engineerKeepsMeasurements() {
        ReportCreateDto dto = FieldScopePolicy.applySurveyScope(dtoWithMeasurements(), true);

        assertEquals(2.5, dto.getDamageLength());
        assertEquals(1.2, dto.getDamageWidth());
    }

    @Test
    void policyReturnsTheSameInstanceForChaining() {
        ReportCreateDto dto = dtoWithMeasurements();
        assertSame(dto, FieldScopePolicy.applySurveyScope(dto, false));
        assertSame(dto, FieldScopePolicy.applySurveyScope(dto, true));
    }

    @Test
    void nullDtoIsTolerated() {
        assertNull(FieldScopePolicy.applySurveyScope(null, false));
        assertNull(FieldScopePolicy.applySurveyScope(null, true));
    }

    @Test
    void capabilityResolutionMatchesTheRoleMatrix() {
        assertFalse(FieldScopePolicy.canSurvey("BARANGAY", capabilities));
        assertFalse(FieldScopePolicy.canSurvey("ROLE_BARANGAY", capabilities));
        assertFalse(FieldScopePolicy.canSurvey(null, capabilities));

        assertTrue(FieldScopePolicy.canSurvey("CPDO Admin", capabilities));
        assertTrue(FieldScopePolicy.canSurvey("ROLE_CPDO ADMIN", capabilities));
        assertTrue(FieldScopePolicy.canSurvey("ADMIN", capabilities));
        assertTrue(FieldScopePolicy.canSurvey("ENGINEER", capabilities));
        assertTrue(FieldScopePolicy.canSurvey("City Engineer", capabilities));
    }

    @Test
    void unknownRoleGetsNoCapability() {
        assertFalse(FieldScopePolicy.canSurvey("INTERN", capabilities));
        assertFalse(FieldScopePolicy.canSurvey("contractor", capabilities));
    }

    @Test
    void nullCapabilityRegistryDeniesRatherThanAllowing() {
        assertFalse(FieldScopePolicy.canSurvey("ADMIN", null),
                "a missing registry must fail closed");
    }
}

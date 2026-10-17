package com.roadwise.backend.controller;

import com.roadwise.backend.dto.BarangayReportCreateDto;
import com.roadwise.backend.model.ReportImage;
import com.roadwise.backend.model.RoadReport;
import com.roadwise.backend.model.User;
import com.roadwise.backend.service.FieldReportSubmissionService;
import com.roadwise.backend.service.RoadInspectionService;
import jakarta.validation.Valid;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The barangay field-capture endpoint: 1-3 photos, a GPS pin, a defect category.
 *
 * <p>Mounted on a distinct path from the legacy {@code POST /api/reports} so both can
 * coexist during the frontend migration. Authorisation is expressed through
 * {@link com.roadwise.backend.security.RoleCapabilities} rather than a second hard-coded
 * role list, and accepts {@code validation} as well so a CPDO administrator can still
 * file on an officer's behalf — the behaviour the legacy endpoint already allowed.</p>
 */
@RestController
@RequestMapping("/api/reports/field")
public class FieldReportController {

    private final FieldReportSubmissionService submissionService;

    public FieldReportController(FieldReportSubmissionService submissionService) {
        this.submissionService = submissionService;
    }

    /**
     * Submit one field report.
     *
     * <p>Status codes are the contract the mobile form codes against:</p>
     * <ul>
     *   <li>201 — stored; body carries the report id and aggregated severity.</li>
     *   <li>400 — malformed request, or a defect category outside the four allowed.</li>
     *   <li>422 — Stage 1 refused a photo. Nothing was persisted. The body names the
     *       offending indices so the officer can retake exactly those frames.</li>
     * </ul>
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@roleCapabilities.fieldReporting(authentication) "
            + "or @roleCapabilities.validation(authentication)")
    @CacheEvict(value = "dashboardStats", allEntries = true)
    public ResponseEntity<?> submit(
            @Valid @ModelAttribute BarangayReportCreateDto createDto,
            @RequestParam(value = "images", required = false) List<MultipartFile> images,
            @RequestParam(value = "perspectives", required = false) List<String> perspectives) {

        User currentUser = getAuthenticatedUser();

        FieldReportSubmissionService.SubmissionResult result;
        try {
            result = submissionService.submit(createDto, images, perspectives, currentUser);
        } catch (IllegalArgumentException unsupportedCategory) {
            // DefectCategory.parseStrict is the only source of this, and its message
            // already lists the allowed values, so it is safe to return verbatim.
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("message", unsupportedCategory.getMessage());
            return ResponseEntity.badRequest().body(body);
        }

        if (result.isRejected()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("message", RoadInspectionService.GATE_REJECTION_MESSAGE);
            body.put("rejected", result.getRejected());
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(summarize(result.getReport()));
    }

    /** Compact view of what was stored — enough for the success screen and tracker. */
    private static Map<String, Object> summarize(RoadReport report) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", report.getId());
        body.put("status", report.getStatus());
        body.put("damageType", report.getDamageType());
        body.put("severity", report.getSeverity());
        body.put("cvSeverityScore", report.getCvSeverityScore());
        body.put("confidence", report.getCvConfidenceScore());
        body.put("cityRoadName", report.getCityRoadName());
        body.put("latitude", report.getLatitude());
        body.put("longitude", report.getLongitude());
        body.put("potentialDuplicate", report.getPotentialDuplicate());
        body.put("dateSubmitted", report.getDateSubmitted());

        List<Map<String, Object>> evidence = new ArrayList<>();
        for (ReportImage image : report.getImages()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", image.getId());
            entry.put("imageUrl", image.getImageUrl());
            entry.put("perspective", image.getPerspectiveType() == null
                    ? null : image.getPerspectiveType().name());
            entry.put("gatePassed", image.getGatePassed());
            entry.put("cvSeverityScore", image.getCvSeverityScore());
            evidence.add(entry);
        }
        body.put("images", evidence);
        return body;
    }

    /** Mirrors the principal contract every other controller relies on. */
    private User getAuthenticatedUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof User user) {
            return user;
        }
        throw new AccessDeniedException("User is not authenticated.");
    }
}

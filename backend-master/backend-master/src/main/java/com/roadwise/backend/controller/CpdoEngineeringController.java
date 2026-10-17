package com.roadwise.backend.controller;

import com.roadwise.backend.dto.CpdoSurveyDto;
import com.roadwise.backend.model.CityRoad;
import com.roadwise.backend.model.ReportStatus;
import com.roadwise.backend.model.RoadReport;
import com.roadwise.backend.model.SurfaceType;
import com.roadwise.backend.model.User;
import com.roadwise.backend.repository.CityRoadRepository;
import com.roadwise.backend.repository.RoadReportRepository;
import com.roadwise.backend.service.PriorityCalibrationService;
import com.roadwise.backend.service.PriorityRankingService;
import com.roadwise.backend.service.ReportStatusLogService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * CPDO engineering surface: technical survey capture, the ranked masterlist, and the
 * calibration intake that feeds the adaptive weighting.
 *
 * <p>Authorisation uses {@link com.roadwise.backend.security.RoleCapabilities} so the
 * spec's three tiers map onto the five role labels the database already holds:
 * {@code technicalSurvey} covers CPDO administrators and City Engineers (the "CEO field
 * engineers" of the specification), {@code priorityRanking} is CPDO-only.</p>
 *
 * <p>Deliberately separate from {@code RoadReportController}: that controller owns the
 * barangay submission and CEO workflow. Adding engineering-only writes there would
 * keep the god-object shape the refactor is meant to remove.</p>
 */
@RestController
@RequestMapping("/api/cpdo")
public class CpdoEngineeringController {

    /** Statuses that can appear on the priority masterlist. */
    private static final List<String> RANKABLE_STATUSES = List.of(
            ReportStatus.VALIDATED.getLabel(),
            ReportStatus.DISPATCHED_TO_CEO.getLabel());

    private final RoadReportRepository reportRepository;
    private final CityRoadRepository cityRoadRepository;
    private final PriorityRankingService rankingService;
    private final PriorityCalibrationService calibrationService;
    private final ReportStatusLogService statusLogService;

    public CpdoEngineeringController(RoadReportRepository reportRepository,
                                     CityRoadRepository cityRoadRepository,
                                     PriorityRankingService rankingService,
                                     PriorityCalibrationService calibrationService,
                                     ReportStatusLogService statusLogService) {
        this.reportRepository = reportRepository;
        this.cityRoadRepository = cityRoadRepository;
        this.rankingService = rankingService;
        this.calibrationService = calibrationService;
        this.statusLogService = statusLogService;
    }

    // ------------------------------------------------------------------ survey

    /**
     * Append or amend the technical survey on an existing field report.
     *
     * <p>Partial updates are the norm: an engineer measures length and width in the
     * street and cores for depth later. Only non-null DTO fields are applied, so an
     * incomplete save never erases a measurement that was already recorded.</p>
     */
    @PostMapping("/reports/{id}/survey")
    @PreAuthorize("@roleCapabilities.technicalSurvey(authentication)")
    @Transactional
    public ResponseEntity<?> recordSurvey(@PathVariable Long id,
                                          @Valid @RequestBody CpdoSurveyDto surveyDto) {
        RoadReport report = reportRepository.findById(id).orElse(null);
        if (report == null) {
            return notFound(id);
        }
        ReportStatus status = safeStatus(report);
        if (status == ReportStatus.ARCHIVED || status == ReportStatus.CLOSED) {
            return conflict("Report PRJ-" + id + " is " + report.getStatus()
                    + " and can no longer be surveyed.");
        }

        SurfaceType surfaceType = null;
        if (surveyDto.getSurfaceType() != null && !surveyDto.getSurfaceType().isBlank()) {
            surfaceType = SurfaceType.parse(surveyDto.getSurfaceType()).orElse(null);
            if (surfaceType == null) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("message", "Unsupported surface type. Expected 'Portland Cement Concrete' "
                        + "or 'Asphalt Concrete'.");
                return ResponseEntity.badRequest().body(body);
            }
        }

        if (surveyDto.getDamageLength() != null) {
            report.setDamageLength(surveyDto.getDamageLength());
        }
        if (surveyDto.getDamageWidth() != null) {
            report.setDamageWidth(surveyDto.getDamageWidth());
        }
        if (surveyDto.getDamageDepth() != null) {
            report.setDamageDepth(surveyDto.getDamageDepth());
        }
        if (surfaceType != null) {
            report.setSurfaceType(surfaceType.name());
        }
        if (surveyDto.getCpdoRepairPlan() != null) {
            report.setCpdoRepairPlan(surveyDto.getCpdoRepairPlan());
        }

        User surveyor = getAuthenticatedUser();
        report.setSurveyedBy(surveyor);

        // Re-derive the ranking keys immediately: the footprint tie-breaker depends on
        // the measurements that were just recorded.
        CityRoad road = linkedRoad(report).orElse(null);
        rankingService.applyScores(report, road);
        RoadReport saved = reportRepository.save(report);

        statusLogService.log(saved, "SURVEY_RECORDED", saved.getStatus(), saved.getStatus(),
                "Technical survey recorded by " + surveyor.getUsername() + ".",
                surveyor, surveyor.getUsername(), surveyor.getRole(), null);

        PriorityCalibrationService.Weights weights = calibrationService.currentWeights();
        return ResponseEntity.ok(describe(saved, weights));
    }

    // --------------------------------------------------------------- masterlist

    /**
     * Ranked priority masterlist. Read-only: scores are computed in memory so a GET
     * never mutates rows (that is {@code /priority/recalculate}'s job).
     */
    @GetMapping("/priority/masterlist")
    @PreAuthorize("@roleCapabilities.priorityRanking(authentication) "
            + "or @roleCapabilities.operationalStatus(authentication)")
    @Transactional(readOnly = true)
    public ResponseEntity<?> masterlist() {
        List<RoadReport> rankable = loadRankable();
        List<RoadReport> ordered = rankingService.rank(rankable);
        PriorityCalibrationService.Weights weights = calibrationService.currentWeights();

        List<Map<String, Object>> rows = new ArrayList<>();
        for (RoadReport report : ordered) {
            rows.add(describe(report, weights));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", rows.size());
        body.put("weights", weightsPayload(weights));
        body.put("reports", rows);
        return ResponseEntity.ok(body);
    }

    /** Persist tier/footprint for every rankable report and return the new order. */
    @PostMapping("/priority/recalculate")
    @PreAuthorize("@roleCapabilities.priorityRanking(authentication)")
    @Transactional
    public ResponseEntity<?> recalculate() {
        List<RoadReport> rankable = loadRankable();
        List<RoadReport> ordered = rankingService.rank(rankable);
        reportRepository.saveAll(ordered);
        PriorityCalibrationService.Weights weights = calibrationService.currentWeights();

        List<Map<String, Object>> rows = new ArrayList<>();
        for (RoadReport report : ordered) {
            rows.add(describe(report, weights));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("recalculated", rows.size());
        body.put("weights", weightsPayload(weights));
        body.put("reports", rows);
        return ResponseEntity.ok(body);
    }

    // ---------------------------------------------------------------- calibration

    /**
     * Fold one historical field audit into the RLS calibration.
     *
     * <p>Raw features rather than normalized ones so the caller does not need to know
     * the service's normalization constants.</p>
     */
    @PostMapping("/priority/audits")
    @PreAuthorize("@roleCapabilities.priorityRanking(authentication)")
    public ResponseEntity<?> recordAudit(@RequestBody AuditRequest auditRequest) {
        if (auditRequest == null || auditRequest.observedPriority() == null) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("message", "observedPriority is required.");
            return ResponseEntity.badRequest().body(body);
        }

        int severity = auditRequest.severity() == null ? 0 : auditRequest.severity();
        double severityFeature = severity <= 0 ? 0.0 : Math.min(3, severity) / 3.0;

        PriorityCalibrationService.Weights updated = calibrationService.recordAudit(
                new PriorityCalibrationService.AuditObservation(
                        severityFeature,
                        Boolean.TRUE.equals(auditRequest.coreArterial()),
                        auditRequest.areaScore() == null ? 0.0 : auditRequest.areaScore(),
                        auditRequest.depthCm() == null ? 0.0 : auditRequest.depthCm(),
                        auditRequest.observedPriority()));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("weights", weightsPayload(updated));
        return ResponseEntity.ok(body);
    }

    /** Raw audit input; the service owns normalization. */
    public record AuditRequest(Integer severity, Boolean coreArterial, Double areaScore,
                               Double depthCm, Double observedPriority) {
    }

    // ------------------------------------------------------------------ helpers

    private List<RoadReport> loadRankable() {
        List<RoadReport> rankable = new ArrayList<>();
        for (String status : RANKABLE_STATUSES) {
            rankable.addAll(reportRepository.findByStatusIgnoreCase(status));
        }
        return rankable;
    }

    private Optional<CityRoad> linkedRoad(RoadReport report) {
        if (report.getCityRoadFkId() == null) {
            return Optional.empty();
        }
        return cityRoadRepository.findById(report.getCityRoadFkId());
    }

    private static ReportStatus safeStatus(RoadReport report) {
        try {
            return ReportStatus.fromString(report.getStatus());
        } catch (RuntimeException unknown) {
            return ReportStatus.PENDING_VALIDATION;
        }
    }

    private Map<String, Object> describe(RoadReport report,
                                         PriorityCalibrationService.Weights weights) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", report.getId());
        row.put("status", report.getStatus());
        row.put("cityRoadName", report.getCityRoadName());
        row.put("cityRoadCode", report.getCityRoadId());
        row.put("damageType", report.getDamageType());
        row.put("severity", report.getSeverity());
        row.put("cvSeverityScore", report.getCvSeverityScore());

        // CPDO survey inputs, echoed so the dashboard form can be populated.
        row.put("damageLength", report.getDamageLength());
        row.put("damageWidth", report.getDamageWidth());
        row.put("damageDepth", report.getDamageDepth());
        row.put("surfaceType", report.getSurfaceType());
        row.put("cpdoRepairPlan", report.getCpdoRepairPlan());
        row.put("surveyedBy", report.getSurveyedBy() == null
                ? null : report.getSurveyedBy().getUsername());

        int tier = report.getTierScore() == null ? PriorityRankingService.TIER_LOW : report.getTierScore();
        row.put("tierScore", tier);
        row.put("tierLabel", tierLabel(tier));
        row.put("tierBadge", rankingService.badgeFor(tier));
        row.put("areaScore", report.getAreaScore());
        row.put("priorityScore", round(calibrationService.priorityScore(report, weights)));
        row.put("latitude", report.getLatitude());
        row.put("longitude", report.getLongitude());
        return row;
    }

    private static String tierLabel(int tier) {
        return switch (tier) {
            case PriorityRankingService.TIER_HIGH -> "HIGH PRIORITY";
            case PriorityRankingService.TIER_MEDIUM -> "MEDIUM PRIORITY";
            default -> "LOW PRIORITY";
        };
    }

    private static Map<String, Object> weightsPayload(PriorityCalibrationService.Weights weights) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("severity", round(weights.severity()));
        payload.put("coreArterial", round(weights.coreArterial()));
        payload.put("area", round(weights.area()));
        payload.put("depth", round(weights.depth()));
        payload.put("auditCount", weights.auditCount());
        return payload;
    }

    private static double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    private static ResponseEntity<?> notFound(Long id) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "Report PRJ-" + id + " was not found.");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    private static ResponseEntity<?> conflict(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", message);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    private User getAuthenticatedUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof User user) {
            return user;
        }
        throw new AccessDeniedException("User is not authenticated.");
    }
}

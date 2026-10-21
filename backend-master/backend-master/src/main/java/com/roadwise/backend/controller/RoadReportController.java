package com.roadwise.backend.controller;

import com.roadwise.backend.config.LiveUpdatePublisher;
import com.roadwise.backend.dto.PageResponseDto;
import com.roadwise.backend.dto.ReportCreateDto;
import com.roadwise.backend.dto.ReportListDto;
import com.roadwise.backend.model.*;
import com.roadwise.backend.repository.BarangayRepository;
import com.roadwise.backend.repository.CityRoadRepository;
import com.roadwise.backend.repository.RoadReportRepository;
import com.roadwise.backend.repository.UserRepository;
import com.roadwise.backend.service.*;
import com.roadwise.backend.util.ExifGpsExtractor;
import jakarta.persistence.criteria.Predicate;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;


import com.roadwise.backend.security.FieldScopePolicy;
import com.roadwise.backend.security.RoleCapabilities;
@RestController
@RequestMapping("/api/reports")
public class RoadReportController {

    @Autowired
    private RoadReportRepository repository;

    // Resolves the spec's three tiers onto the five role labels the users table
    // holds; see RoleCapabilities. Used to scope the engineering fields below.
    @Autowired
    private RoleCapabilities roleCapabilities;

    @Autowired
    private DashboardStatsService dashboardStatsService;

    @Autowired
    private BarangayRepository barangayRepository;

    @Autowired
    private CityRoadRepository cityRoadRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EmailService emailService;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private ActivityLogService activityLogService;

    @Autowired
    private RoadInspectionService aiService;

    @Autowired
    private SupabaseStorageService supabaseStorageService;

    @Autowired
    private LiveUpdatePublisher liveUpdatePublisher;

    @Autowired
    private ReportStatusLogService reportStatusLogService;

    @Autowired
    private ReportWorkflowService reportWorkflowService;

    @Autowired
    private DuplicateDetectionService duplicateDetectionService;

    private User getAuthenticatedUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof User) {
            return (User) auth.getPrincipal();
        }
        throw new AccessDeniedException("User is not authenticated.");
    }

    private boolean isBarangayRole(User user) {
        if (user == null || user.getRole() == null) return false;
        String r = user.getRole().toUpperCase();
        return r.contains("BARANGAY");
    }

    /**
     * Enforces that a Barangay Official can only touch reports belonging to the
     * barangay they are assigned to. Fails closed: an unassigned official, or a
     * report with no barangay, is denied rather than silently allowed.
     */
    private void enforceBarangayReportAccess(User user, RoadReport report) {
        if (!isBarangayRole(user)) {
            return;
        }
        Long userBrgy = user.getBarangay() != null ? user.getBarangay().getId() : null;
        Long reportBrgy = report.getBarangay() != null ? report.getBarangay().getId() : null;
        if (userBrgy == null || reportBrgy == null || !userBrgy.equals(reportBrgy)) {
            throw new AccessDeniedException("Access denied: report is outside your assigned barangay.");
        }
    }

    private Long getAdminId() {
        return userRepository.findFirstByRoleInIgnoreCase(List.of("cpdo admin", "admin"))
                .map(User::getId)
                .orElse(1L);
    }

    /**
     * Only road-confirmed, adequately confident predictions get their image
     * published to the public bucket. Everything else (INVALID_INPUT,
     * LOW_IMAGE_QUALITY, NON_ROAD_OR_UNCERTAIN, UNCERTAIN_PREDICTION,
     * MODEL_ERROR, AI_UNAVAILABLE, AI_ERROR or any reviewRequired result)
     * goes to quarantine pending human validation.
     */
    private boolean isPublishableAiResult(RoadInspectionService.InspectionResult aiResult) {
        if (aiResult == null) return false;
        if (aiResult.isReviewRequired()) return false;
        if ("Unassessed".equalsIgnoreCase(aiResult.getSeverity())) return false;
        String status = aiResult.getStatus() == null ? "" : aiResult.getStatus().toUpperCase();
        return status.equals("CONFIRMED") || status.equals("ROAD_CONFIRMED");
    }

    private Long getCeoId() {
        return userRepository.findFirstByRoleInIgnoreCase(List.of("engineer", "city engineer"))
                .map(User::getId)
                .orElse(null);
    }

    /**
     * Resolves a client-supplied road reference. The submission form may send
     * either the numeric database id or the human-readable {@code road_id} code
     * (e.g. "3142000000 01"), so both are accepted. Unknown references are
     * rejected instead of being silently ignored.
     */
    private CityRoad resolveCityRoad(String roadRef) {
        String ref = roadRef == null ? "" : roadRef.trim();
        if (ref.isEmpty()) {
            throw new IllegalArgumentException("City road reference is empty.");
        }
        try {
            Optional<CityRoad> byId = cityRoadRepository.findById(Long.valueOf(ref));
            if (byId.isPresent()) {
                return byId.get();
            }
        } catch (NumberFormatException ignored) {
            // Not a numeric id; fall through to the road-code lookup.
        }
        return cityRoadRepository.findByRoadId(ref)
                .orElseThrow(() -> new IllegalArgumentException("Unknown city road: " + ref));
    }

    /**
     * Resolves the coordinates to store for a submission. An explicitly
     * provided fix always wins; otherwise the attached photo's EXIF GPS tags
     * are used when present. EXIF recovery is best-effort and never blocks
     * the submission.
     *
     * @return a two-element array {@code [latitude, longitude]}.
     */
    private Double[] resolveReportCoordinates(Double latitude, Double longitude,
                                              MultipartFile imageFile) {
        Double lat = latitude;
        Double lng = longitude;
        boolean missing = lat == null || lng == null || (lat == 0.0 && lng == 0.0);
        if (missing && imageFile != null && !imageFile.isEmpty()) {
            try {
                Optional<ExifGpsExtractor.GpsCoordinates> exif =
                        ExifGpsExtractor.extract(imageFile.getBytes());
                if (exif.isPresent()) {
                    lat = exif.get().latitude();
                    lng = exif.get().longitude();
                    System.out.println("📍 [EXIF GEOTAG] Coordinates recovered from photo metadata: "
                            + lat + ", " + lng);
                }
            } catch (Exception ignored) {
                // A missing or unreadable fix must never block the submission.
            }
        }
        return new Double[]{lat, lng};
    }

    // ==========================================
    // 1. CREATE REPORT
    // ==========================================
    @PostMapping(consumes = {"multipart/form-data"})
    @PreAuthorize("hasAnyRole('BARANGAY', 'ADMIN', 'CPDO Admin')")
    @CacheEvict(value = "dashboardStats", allEntries = true)
    @Transactional
    public RoadReport createReport(
            @ModelAttribute ReportCreateDto createDto,
            @RequestParam(value = "imageFile", required = false) MultipartFile imageFile,
            HttpServletRequest request) {

        User currentUser = getAuthenticatedUser();

        // Module 2 boundary. A caller without TECHNICAL_SURVEY records what and where
        // only; their damage measurements are discarded after binding and before
        // persistence. Discarding rather than rejecting is deliberate: the request is
        // still a valid field report, and a 4xx here would cost the photos and the GPS
        // pin as well as the two extra parameters. The fields that belong solely to the
        // CPDO survey (depth, surface type, repair plan) are not on ReportCreateDto at
        // all, so they cannot be bound in the first place.
        FieldScopePolicy.applySurveyScope(
                createDto, FieldScopePolicy.canSurvey(currentUser.getRole(), roleCapabilities));
        RoadReport report = new RoadReport();

        report.setDamageDescription(createDto.getDamageDescription());
        report.setDamageType(createDto.getDamageType());
        report.setLength(createDto.getLength());
        report.setWidth(createDto.getWidth());
        report.setLengthOfCulverts(createDto.getLengthOfCulverts());
        report.setNumberOfBridges(createDto.getNumberOfBridges());
        // Automatic geotagging: fall back to the photo's EXIF GPS fix when the
        // submission carries no usable coordinates (absent, or the 0.0/0.0
        // sentinel the form sends when no map pin was dropped).
        Double[] resolvedCoords = resolveReportCoordinates(
                createDto.getLatitude(), createDto.getLongitude(), imageFile);
        report.setLatitude(resolvedCoords[0]);
        report.setLongitude(resolvedCoords[1]);
        report.setDamageLength(createDto.getDamageLength());
        report.setDamageWidth(createDto.getDamageWidth());
        report.setCityRoadName(createDto.getCityRoadName());

        if (createDto.getCityRoadId() != null && !createDto.getCityRoadId().trim().isEmpty()) {
            CityRoad cityRoad = resolveCityRoad(createDto.getCityRoadId());
            report.setCityRoadFkId(cityRoad.getId());
            report.setCityRoadId(cityRoad.getRoadId());
            if (cityRoad.getRoadName() != null && !cityRoad.getRoadName().trim().isEmpty()) {
                report.setCityRoadName(cityRoad.getRoadName());
            }
            report.setRoadImportance(cityRoad.getRoadImportance());
            report.setRoadType(cityRoad.getRoadType());
            report.setTerrainType(cityRoad.getTerrainType());
        }

        report.setUser(currentUser);
        report.setReportedBy(currentUser.getFirstName() + " " + currentUser.getLastName());
        report.setBarangay(currentUser.getBarangay());

        try {
            if (imageFile != null && !imageFile.isEmpty()) {
                // SAFETY POLICY: validate + infer BEFORE permanent storage.
                // Invalid / low-quality / non-road / uncertain images are never
                // published to the public bucket; they are quarantined for review.
                RoadInspectionService.InspectionResult aiResult;
                try {
                    aiResult = aiService.analyzeSeverity(imageFile);
                } catch (Exception e) {
                    aiResult = RoadInspectionService.InspectionResult
                            .unassessed("AI_UNAVAILABLE", "AI service call failed.");
                }
                report.setSeverity(aiResult.getSeverity());
                report.setCvConfidenceScore(aiResult.getConfidence());

                if (isPublishableAiResult(aiResult)) {
                    String cloudImageUrl = supabaseStorageService.uploadImage(imageFile);
                    report.setDamageImage(cloudImageUrl);
                } else {
                    String quarantineUrl = supabaseStorageService.uploadImageQuarantined(imageFile);
                    report.setDamageImage(quarantineUrl);
                    System.out.println("⚠️ [IMAGE QUARANTINED] AI status=" + aiResult.getStatus()
                            + " reviewRequired=" + aiResult.isReviewRequired());
                }
            } else {
                report.setDamageImage("no_image.jpg");
                report.setSeverity("Unassessed");
                report.setCvConfidenceScore(0.0);
            }

            report.setStatus(ReportStatus.PENDING_VALIDATION.getLabel());

            // Duplicate detection runs before the single persist so the flag and
            // relationship are written atomically with the report. Re-saving a
            // detached entity would re-proxy its lazy associations and break
            // response serialization.
            Optional<RoadReport> duplicateOpt = duplicateDetectionService.findPotentialDuplicate(report);
            duplicateOpt.ifPresent(existingDup -> {
                report.setPotentialDuplicate(true);
                report.setDuplicateOfReportId(existingDup.getId());
            });

            RoadReport savedReport = repository.save(report);
            Long adminId = getAdminId();

            if (duplicateOpt.isPresent()) {
                final Long duplicateId = duplicateOpt.get().getId();
                final Long newReportId = savedReport.getId();
                final String newReportRoad = savedReport.getCityRoadName();
                System.out.println("⚠️ [DUPLICATE FLAGGED] Report PRJ-" + newReportId + " is near existing PRJ-" + duplicateId);
                // A notification is an external effect; announce only once the report commits.
                registerPostCommit(() -> notificationService.sendNotification(
                        adminId,
                        "⚠️ Potential Duplicate Report Flagged",
                        "Report PRJ-" + newReportId + " on " + newReportRoad +
                                " was flagged as a potential duplicate of PRJ-" + duplicateId + ".",
                        "DUPLICATE_WARNING",
                        "REPORT", newReportId
                ));
            }

            reportStatusLogService.log(
                    savedReport,
                    "SUBMITTED",
                    null,
                    ReportStatus.PENDING_VALIDATION.getLabel(),
                    "Road damage report submitted for '" + savedReport.getCityRoadName() + "'.",
                    currentUser,
                    savedReport.getReportedBy(),
                    "Barangay Official",
                    savedReport.getDamageImage()
            );

            final String reportedBy = savedReport.getReportedBy();
            final String roadName = savedReport.getCityRoadName();
            final boolean highSeverity = savedReport.getSeverity() != null
                    && savedReport.getSeverity().equalsIgnoreCase("High");
            final Long reporterId = savedReport.getUser() != null ? savedReport.getUser().getId() : null;

            // Notifications are external effects; announce only once the report commits.
            registerPostCommit(() -> {
                if (highSeverity) {
                    notificationService.sendNotification(
                            adminId,
                            "🚨 CRITICAL HAZARD DETECTED",
                            "System flagged a HIGH severity road damage reported by " + reportedBy + ". Immediate CPDO review required!",
                            "CRITICAL",
                            "REPORT", savedReport.getId()
                    );
                } else {
                    notificationService.sendNotification(
                            adminId,
                            "New Road Damage Report",
                            "A new report has been submitted by " + reportedBy + " and is awaiting CPDO review.",
                            "REPORT",
                            "REPORT", savedReport.getId()
                    );
                }
                if (reporterId != null) {
                    notificationService.sendNotification(
                            reporterId,
                            "Report Submitted Successfully",
                            "Your damage report for " + roadName + " has been received by the CPDO and is awaiting initial validation.",
                            "REPORT",
                            "REPORT", savedReport.getId()
                    );
                }
            });

            activityLogService.log(
                    currentUser,
                    "PROJECT",
                    "REPORT_SUBMITTED",
                    "#PRJ-" + String.format("%04d", savedReport.getId()),
                    "Road damage report submitted for '" + savedReport.getCityRoadName() + "'.",
                    "SUCCESS",
                    request
            );

            // Live-update fan-out is an external effect; publish only once the report commits.
            final List<Long> affectedBarangays = barangaysOf(savedReport);
            registerPostCommit(() -> liveUpdatePublisher.publish(affectedBarangays));
            return savedReport;

        } catch (Exception e) {
            throw new RuntimeException("Failed to upload image or submit report: " + e.getMessage());
        }
    }

    // ==========================================
    // GET REPORTS WITH PAGINATION & FILTERING
    // ==========================================
    /**
     * Report reads run inside a read-only transaction.
     *
     * <p>{@code spring.jpa.open-in-view=false} (application.properties), so the entities a
     * repository call returns are already detached by the time this method maps them.
     * {@link ReportListDto#fromEntity} reads the lazy {@code barangay} association, which
     * without a transaction boundary raises {@code LazyInitializationException} — a 500 for
     * every report that has a barangay, which is every report filed through the form.</p>
     */
    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<?> getAllReports(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "50") int size,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "barangayId", required = false) Long barangayId,
            @RequestParam(value = "severity", required = false) String severity,
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(value = "sort", defaultValue = "id,desc") String sort) {

        User currentUser = getAuthenticatedUser();

        // Enforce maximum page size limit
        int cappedSize = Math.min(Math.max(1, size), 100);

        Specification<RoadReport> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (isBarangayRole(currentUser)) {
                if (currentUser.getBarangay() == null) {
                    throw new AccessDeniedException("Access denied: your account is not assigned to a barangay.");
                }
                predicates.add(cb.equal(root.get("barangay").get("id"), currentUser.getBarangay().getId()));
            } else if (barangayId != null) {
                predicates.add(cb.equal(root.get("barangay").get("id"), barangayId));
            }

            if (status != null && !status.trim().isEmpty()) {
                predicates.add(cb.equal(cb.lower(root.get("status")), status.trim().toLowerCase()));
            }

            if (severity != null && !severity.trim().isEmpty()) {
                predicates.add(cb.equal(cb.lower(root.get("severity")), severity.trim().toLowerCase()));
            }

            if (search != null && !search.trim().isEmpty()) {
                String term = "%" + search.trim().toLowerCase() + "%";
                Predicate searchRoad = cb.like(cb.lower(root.get("cityRoadName")), term);
                Predicate searchDescription = cb.like(cb.lower(root.get("damageDescription")), term);
                predicates.add(cb.or(searchRoad, searchDescription));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };

        String[] sortParts = sort.split(",");
        String sortProperty = sortParts[0];
        Sort.Direction sortDirection = (sortParts.length > 1 && sortParts[1].equalsIgnoreCase("asc")) ? Sort.Direction.ASC : Sort.Direction.DESC;

        // Stable tie-breaker ordering
        Sort stableSort = Sort.by(sortDirection, sortProperty);
        if (!sortProperty.equalsIgnoreCase("id")) {
            stableSort = stableSort.and(Sort.by(Sort.Direction.DESC, "id"));
        }

        Pageable pageable = PageRequest.of(page, cappedSize, stableSort);
        Page<RoadReport> reportPage = repository.findAll(spec, pageable);

        List<ReportListDto> dtoList = reportPage.getContent().stream()
                .map(ReportListDto::fromEntity)
                .toList();

        PageResponseDto<ReportListDto> response = new PageResponseDto<>(
                dtoList,
                reportPage.getNumber(),
                reportPage.getSize(),
                reportPage.getTotalElements(),
                reportPage.getTotalPages(),
                reportPage.isLast()
        );

        return ResponseEntity.ok(response);
    }

    /**
     * Dashboard totals, scoped to the caller's entitlement.
     *
     * <p>The report list caps a Barangay Official to their own barangay; these
     * totals must match, or the endpoint becomes a way to read city-wide
     * volumes the caller cannot list. Administrators and the city engineering
     * office keep the unscoped view.</p>
     *
     * <p>Scoping and caching both live in {@link DashboardStatsService}: the
     * cache key has to include the scope, and a {@code @Cacheable} on this
     * method would be keyed on nothing at all — it would hand whichever scope
     * happened to be computed first to every later caller.</p>
     */
    @GetMapping("/dashboard-stats")
    public ResponseEntity<?> getDashboardStats() {
        User currentUser = getAuthenticatedUser();

        Long scopeBarangayId = null;
        if (isBarangayRole(currentUser)) {
            if (currentUser.getBarangay() == null) {
                throw new AccessDeniedException("Access denied: your account is not assigned to a barangay.");
            }
            scopeBarangayId = currentUser.getBarangay().getId();
        }

        return ResponseEntity.ok(dashboardStatsService.getStats(scopeBarangayId));
    }

    @GetMapping("/{id}")
    // The entity itself is the response body, so Jackson resolves barangay/user after this
    // method — and therefore its transaction — has returned. The boundary keeps the access
    // check below inside a session; the fetch graph on RoadReportRepository.findById is what
    // makes the associations present for the serializer.
    @Transactional(readOnly = true)
    public ResponseEntity<RoadReport> getReportById(@PathVariable Long id) {
        User currentUser = getAuthenticatedUser();
        RoadReport report = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Report not found"));

        enforceBarangayReportAccess(currentUser, report);

        return ResponseEntity.ok(report);
    }

    @GetMapping("/barangay/{barangayId}")
    // Same reasoning as GET /{id}: raw entities are serialized after the transaction ends,
    // so their associations have to arrive with the query rather than on first access.
    @Transactional(readOnly = true)
    public List<RoadReport> getReportsByBarangay(@PathVariable Long barangayId) {
        User currentUser = getAuthenticatedUser();
        if (isBarangayRole(currentUser)) {
            if (currentUser.getBarangay() == null
                    || !currentUser.getBarangay().getId().equals(barangayId)) {
                throw new AccessDeniedException("Access denied: You can only view reports from your own barangay.");
            }
        }
        return repository.findByBarangay_IdOrderByIdDesc(barangayId);
    }

    // ==========================================
    // 2. STATUS UPDATES (CPDO / CEO)
    // ==========================================
    @PutMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('ADMIN', 'CPDO Admin', 'ENGINEER', 'City Engineer')")
    @Transactional
    @CacheEvict(value = "dashboardStats", allEntries = true)
    public ResponseEntity<String> updateReportStatus(
            @PathVariable Long id,
            @RequestBody Map<String, String> payload,
            HttpServletRequest request) {

        User currentUser = getAuthenticatedUser();
        RoadReport report = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Report not found"));

        String previousStatusStr = report.getStatus();
        String newStatusStr = payload.get("status");
        if (newStatusStr == null || newStatusStr.trim().isEmpty()) {
            return ResponseEntity.badRequest().body("Status parameter is required.");
        }
        String adminRemarks = payload.get("adminRemarks");

        ReportStatus currentStatusEnum = ReportStatus.fromString(previousStatusStr);
        ReportStatus targetStatusEnum = ReportStatus.fromString(newStatusStr);

        // Role matrix (state machine + authority check) so this generic
        // endpoint cannot bypass administrative-only flows such as dispatch.
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        List<String> callerRoles = auth == null ? List.of() : auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();
        boolean callerIsAdmin = callerRoles.stream().anyMatch(role -> {
            String normalized = role == null ? "" : role.replaceFirst("^ROLE_", "");
            return normalized.equalsIgnoreCase("ADMIN") || normalized.equalsIgnoreCase("CPDO Admin");
        });

        boolean isAutoArchivedNoDamage = false;
        if (targetStatusEnum == ReportStatus.VALIDATED) {
            boolean hasNoDamageImage = report.getDamageImage() == null ||
                    report.getDamageImage().trim().isEmpty() ||
                    report.getDamageImage().trim().equalsIgnoreCase("no_image.jpg");

            if (hasNoDamageImage) {
                targetStatusEnum = ReportStatus.ARCHIVED;
                isAutoArchivedNoDamage = true;
                if (adminRemarks == null || adminRemarks.trim().isEmpty()) {
                    adminRemarks = "Inspected by CPDO: No physical road damage detected. Ticket archived as 'No Action Required'.";
                }
            }
        }

        reportWorkflowService.validateTransitionForRole(currentStatusEnum, targetStatusEnum, callerRoles);

        // Every terminal repair outcome requires evidence. Without this, a report
        // could go In Progress -> Resolved -> Closed with no proof of repair.
        if (targetStatusEnum == ReportStatus.COMPLETED
                || targetStatusEnum == ReportStatus.RESOLVED
                || targetStatusEnum == ReportStatus.CLOSED) {
            boolean hasProofImage = report.getProofOfRepairImage() != null &&
                    !report.getProofOfRepairImage().trim().isEmpty();
            if (!hasProofImage) {
                return ResponseEntity.badRequest().body("Repair proof of image is required before resolution or closure.");
            }
        }

        if (targetStatusEnum.getLabel().equalsIgnoreCase(previousStatusStr)) {
            return ResponseEntity.ok("ALREADY_UPDATED");
        }

        String targetDateStr = payload.get("targetCompletionDate");
        if (targetDateStr != null && !targetDateStr.trim().isEmpty()) {
            // Deadline changes belong to administrative scheduling, not to the
            // repair workflow.
            if (!callerIsAdmin) {
                return ResponseEntity.status(403)
                        .body("Deadline changes require administrative scheduling approval.");
            }
            try {
                report.setTargetCompletionDate(LocalDate.parse(targetDateStr.trim()));
            } catch (Exception ignored) {}
        }

        report.setStatus(targetStatusEnum.getLabel());

        if (targetStatusEnum == ReportStatus.CLOSED || targetStatusEnum == ReportStatus.RESOLVED ||
                targetStatusEnum == ReportStatus.ARCHIVED || targetStatusEnum == ReportStatus.COMPLETED) {
            LocalDateTime nowPst = LocalDateTime.now(ZoneId.of("Asia/Manila"));
            report.setDateArchived(nowPst);
            report.setActualCompletionDate(nowPst.toLocalDate());
        }

        if (adminRemarks != null) {
            report.setAdminRemarks(adminRemarks);
        }

        repository.save(report);
        sendStatusUpdateEmail(report, targetStatusEnum.getLabel(), adminRemarks);

        Long adminId = getAdminId();
        if (targetStatusEnum == ReportStatus.COMPLETED) {
            notificationService.sendNotification(adminId, "Report Status Update", "Report ID PRJ-" + report.getId() + " status updated to: Completed", "REPORT", "REPORT", report.getId());
        }

        reportStatusLogService.log(
                report,
                "STATUS_UPDATE",
                previousStatusStr,
                targetStatusEnum.getLabel(),
                adminRemarks,
                currentUser,
                currentUser.getFirstName() + " " + currentUser.getLastName(),
                currentUser.getRole(),
                report.getProofOfRepairImage()
        );

        activityLogService.log(
                currentUser,
                "PROJECT",
                "STATUS_UPDATED_" + targetStatusEnum.name(),
                "#PRJ-" + String.format("%04d", report.getId()),
                "Project status transitioned to '" + targetStatusEnum.getLabel() + "'",
                "SUCCESS",
                request
        );

        liveUpdatePublisher.publish(barangaysOf(report));
        return ResponseEntity.ok(isAutoArchivedNoDamage ? "ARCHIVED_NO_IMAGE" : "SUCCESS");
    }

    // ==========================================
    // 3. UPDATE / RESUBMIT REPORT
    // ==========================================
    @PutMapping("/update/{id}")
    @PreAuthorize("hasAnyRole('BARANGAY', 'ADMIN', 'CPDO Admin')")
    @Transactional
    @CacheEvict(value = "dashboardStats", allEntries = true)
    public ResponseEntity<?> updateReport(@PathVariable Long id,
                                          @RequestParam(value = "damageDescription", required = false) String description,
                                          @RequestParam(value = "length", required = false) Double length,
                                          @RequestParam(value = "width", required = false) Double width,
                                          @RequestParam(value = "lengthOfCulverts", required = false) Double lengthOfCulverts,
                                          @RequestParam(value = "numberOfBridges", required = false) Integer numberOfBridges,
                                          @RequestParam(value = "latitude", required = false) Double latitude,
                                          @RequestParam(value = "longitude", required = false) Double longitude,
                                          @RequestParam(value = "damageType", required = false) String damageType,
                                          @RequestParam(value = "damageLength", required = false) Double damageLength,
                                          @RequestParam(value = "damageWidth", required = false) Double damageWidth,
                                          @RequestParam(value = "cityRoadId", required = false) String cityRoadId,
                                          @RequestParam(value = "imageFile", required = false) MultipartFile imageFile,
                                          HttpServletRequest request) {
        User currentUser = getAuthenticatedUser();
        RoadReport existingReport = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Report not found"));

        enforceBarangayReportAccess(currentUser, existingReport);

        String previousStatus = existingReport.getStatus();

        // Only reports still in the barangay intake/rework stage may be edited.
        // Terminal (Completed/Closed/Resolved/Archived) or in-flight reports must
        // not be silently reset back to Pending Validation.
        String editableStatus = previousStatus == null ? "" : previousStatus.trim().toLowerCase();
        if (!(editableStatus.equals("pending validation")
                || editableStatus.equals("rejected")
                || editableStatus.equals("resubmitted"))) {
            return ResponseEntity.status(409).body(Map.of("error",
                    "This report can no longer be edited because it is already in the '" + previousStatus + "' stage."));
        }

        if (cityRoadId != null && !cityRoadId.trim().isEmpty()) {
            CityRoad cityRoad = resolveCityRoad(cityRoadId);
            existingReport.setCityRoadFkId(cityRoad.getId());
            existingReport.setCityRoadId(cityRoad.getRoadId());
            if (cityRoad.getRoadName() != null && !cityRoad.getRoadName().trim().isEmpty()) {
                existingReport.setCityRoadName(cityRoad.getRoadName());
            }
            existingReport.setRoadImportance(cityRoad.getRoadImportance());
            existingReport.setRoadType(cityRoad.getRoadType());
            existingReport.setTerrainType(cityRoad.getTerrainType());
        }

        if (description != null) existingReport.setDamageDescription(description);
        if (length != null) existingReport.setLength(length);
        if (width != null) existingReport.setWidth(width);
        if (lengthOfCulverts != null) existingReport.setLengthOfCulverts(lengthOfCulverts);
        if (numberOfBridges != null) existingReport.setNumberOfBridges(numberOfBridges);
        if (latitude != null) existingReport.setLatitude(latitude);
        if (longitude != null) existingReport.setLongitude(longitude);
        if (damageType != null) existingReport.setDamageType(damageType);
        if (damageLength != null) existingReport.setDamageLength(damageLength);
        if (damageWidth != null) existingReport.setDamageWidth(damageWidth);

        // Automatic geotagging on resubmission: when neither explicit
        // coordinates nor a stored fix exist, recover the replacement photo's
        // EXIF GPS fix (explicit values always win).
        if (latitude == null && longitude == null
                && (existingReport.getLatitude() == null || existingReport.getLongitude() == null
                    || (existingReport.getLatitude() == 0.0 && existingReport.getLongitude() == 0.0))
                && imageFile != null && !imageFile.isEmpty()) {
            try {
                Optional<ExifGpsExtractor.GpsCoordinates> exif =
                        ExifGpsExtractor.extract(imageFile.getBytes());
                if (exif.isPresent()) {
                    existingReport.setLatitude(exif.get().latitude());
                    existingReport.setLongitude(exif.get().longitude());
                    System.out.println("📍 [EXIF GEOTAG] Coordinates recovered from replacement photo: "
                            + exif.get().latitude() + ", " + exif.get().longitude());
                }
            } catch (Exception ignored) {
                // A missing or unreadable fix must never block the update.
            }
        }

        try {
            if (imageFile != null && !imageFile.isEmpty()) {
                // SAFETY POLICY: run validation/inference before deciding where
                // the image is stored (published vs quarantined).
                RoadInspectionService.InspectionResult aiResult;
                try {
                    aiResult = aiService.analyzeSeverity(imageFile);
                } catch (Exception e) {
                    aiResult = RoadInspectionService.InspectionResult
                            .unassessed("AI_UNAVAILABLE", "AI service call failed.");
                }
                existingReport.setSeverity(aiResult.getSeverity());
                existingReport.setCvConfidenceScore(aiResult.getConfidence());

                if (isPublishableAiResult(aiResult)) {
                    existingReport.setDamageImage(supabaseStorageService.uploadImage(imageFile));
                } else {
                    existingReport.setDamageImage(supabaseStorageService.uploadImageQuarantined(imageFile));
                    System.out.println("⚠️ [IMAGE QUARANTINED] AI status=" + aiResult.getStatus()
                            + " reviewRequired=" + aiResult.isReviewRequired());
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to process updated image: " + e.getMessage());
        }

        boolean isResubmitted = false;
        if ("Rejected".equalsIgnoreCase(previousStatus)) {
            existingReport.setStatus(ReportStatus.RESUBMITTED.getLabel());
            isResubmitted = true;
        } else {
            existingReport.setStatus(ReportStatus.PENDING_VALIDATION.getLabel());
        }

        existingReport.setAdminRemarks(null);
        repository.save(existingReport);

        reportStatusLogService.log(
                existingReport,
                isResubmitted ? "RESUBMITTED" : "REPORT_UPDATED",
                previousStatus,
                existingReport.getStatus(),
                isResubmitted ? "Report revised and resubmitted with updated survey details." : "Report specifications updated.",
                currentUser,
                existingReport.getReportedBy(),
                "Barangay Official",
                existingReport.getDamageImage()
        );

        activityLogService.log(
                currentUser,
                "PROJECT",
                isResubmitted ? "REPORT_RESUBMITTED" : "REPORT_UPDATED",
                "#PRJ-" + String.format("%04d", existingReport.getId()),
                "Report details updated for " + existingReport.getCityRoadName(),
                "SUCCESS",
                request
        );

        liveUpdatePublisher.publish(barangaysOf(existingReport));
        return ResponseEntity.ok(existingReport);
    }

    // ==========================================
    // 4. BATCH DISPATCH TO CEO
    // ==========================================
    @PutMapping("/dispatch-masterlist")
    @PreAuthorize("hasAnyRole('ADMIN', 'CPDO Admin')")
    @Transactional
    @CacheEvict(value = "dashboardStats", allEntries = true)
    public ResponseEntity<String> dispatchMasterlistToCEO(HttpServletRequest request) {
        User currentUser = getAuthenticatedUser();
        List<RoadReport> validatedReports = repository.findByStatusIgnoreCase("Validated");
        int dispatchedCount = 0;

        for (RoadReport report : validatedReports) {
            boolean hasValidImage = report.getDamageImage() != null &&
                    !report.getDamageImage().trim().isEmpty() &&
                    !report.getDamageImage().trim().equalsIgnoreCase("no_image.jpg");

            String prev = report.getStatus();
            if (hasValidImage) {
                report.setStatus(ReportStatus.DISPATCHED_TO_CEO.getLabel());
                dispatchedCount++;

                reportStatusLogService.log(
                        report,
                        "DISPATCHED",
                        prev,
                        ReportStatus.DISPATCHED_TO_CEO.getLabel(),
                        "Dispatched by CPDO to the City Engineer priority pool.",
                        currentUser,
                        currentUser.getFirstName() + " " + currentUser.getLastName(),
                        "ADMIN",
                        null
                );
            } else {
                report.setStatus(ReportStatus.ARCHIVED.getLabel());
                LocalDateTime nowPst = LocalDateTime.now(ZoneId.of("Asia/Manila"));
                report.setDateArchived(nowPst);
                report.setActualCompletionDate(nowPst.toLocalDate());
                report.setAdminRemarks("Auto-archived during dispatch: No photographic damage evidence.");

                reportStatusLogService.log(
                        report,
                        "ARCHIVED_NO_DAMAGE",
                        prev,
                        ReportStatus.ARCHIVED.getLabel(),
                        "Auto-archived during dispatch filter.",
                        currentUser,
                        currentUser.getFirstName() + " " + currentUser.getLastName(),
                        "ADMIN",
                        null
                );
            }
        }

        if (!validatedReports.isEmpty()) {
            repository.saveAll(validatedReports);
        }

        if (dispatchedCount > 0) {
            activityLogService.log(
                    currentUser,
                    "PROJECT",
                    "MASTERLIST_DISPATCHED",
                    "CEO_PRIORITY_QUEUE",
                    "CPDO dispatched " + dispatchedCount + " validated reports to CEO priority queue.",
                    "SUCCESS",
                    request
            );
            liveUpdatePublisher.publish(barangaysOf(validatedReports));
            return ResponseEntity.ok("Successfully dispatched " + dispatchedCount + " prioritized reports to the CEO!");
        } else {
            liveUpdatePublisher.publish(barangaysOf(validatedReports));
            return ResponseEntity.badRequest().body("No 'Validated' reports found to dispatch.");
        }
    }

    // ==========================================
    // 5. CEO BATCH DEFER REPAIRS
    // ==========================================
    @PostMapping("/batch/defer")
    @PreAuthorize("hasAnyRole('ENGINEER', 'City Engineer', 'ADMIN', 'CPDO Admin')")
    @Transactional
    @CacheEvict(value = "dashboardStats", allEntries = true)
    public ResponseEntity<?> batchDeferReports(@RequestBody Map<String, Object> payload, HttpServletRequest request) {
        User currentUser = getAuthenticatedUser();
        String reason = (String) payload.get("repairRemarks");
        Object idsObj = payload.get("reportIds");

        if (reason == null || reason.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Deferral reason is required."));
        }
        if (idsObj == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "No reports selected for deferral."));
        }

        List<Long> reportIds = new ArrayList<>();
        if (idsObj instanceof List) {
            for (Object id : (List<?>) idsObj) {
                reportIds.add(Long.valueOf(id.toString()));
            }
        }

        List<RoadReport> reportsToDefer = repository.findAllById(reportIds);

        for (RoadReport r : reportsToDefer) {
            String prev = r.getStatus();
            reportWorkflowService.validateTransition(ReportStatus.fromString(prev), ReportStatus.PENDING_BUDGET);
            r.setStatus(ReportStatus.PENDING_BUDGET.getLabel());
            r.setRepairRemarks(reason);

            reportStatusLogService.log(
                    r,
                    "PENDING_BUDGET",
                    prev,
                    ReportStatus.PENDING_BUDGET.getLabel(),
                    reason,
                    currentUser,
                    currentUser.getFirstName() + " " + currentUser.getLastName(),
                    "ENGINEER",
                    null
            );
        }

        repository.saveAll(reportsToDefer);

        activityLogService.log(
                currentUser,
                "PROJECT",
                "REPAIRS_BATCH_DEFERRED",
                "BATCH_DEFERRAL",
                "CEO deferred " + reportsToDefer.size() + " reports to Pending Budget. Reason: " + reason,
                "SUCCESS",
                request
        );

        liveUpdatePublisher.publish(barangaysOf(reportsToDefer));
        return ResponseEntity.ok().body(Map.of("message", "Successfully deferred " + reportsToDefer.size() + " selected reports."));
    }

    // ==========================================
    // 6. CEO COMPLETE REPORT
    // ==========================================
    @PostMapping(value = "/{id}/complete", consumes = {"multipart/form-data"})
    @PreAuthorize("hasAnyRole('ENGINEER', 'City Engineer', 'ADMIN', 'CPDO Admin')")
    @Transactional
    @CacheEvict(value = "dashboardStats", allEntries = true)
    public ResponseEntity<?> completeReport(
            @PathVariable Long id,
            @RequestParam(value = "repairRemarks", required = false) String repairRemarks,
            @RequestParam(value = "proofImage", required = false) MultipartFile proofImage,
            HttpServletRequest request) {

        User currentUser = getAuthenticatedUser();
        RoadReport report = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Report not found"));

        if (proofImage == null || proofImage.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Proof of repair image is required to complete the report."));
        }

        String previousStatus = report.getStatus();
        reportWorkflowService.validateTransition(ReportStatus.fromString(previousStatus), ReportStatus.COMPLETED);

        try {
            String cloudProofUrl = supabaseStorageService.uploadImage(proofImage);
            report.setProofOfRepairImage(cloudProofUrl);
        } catch (Exception e) {
            throw new RuntimeException("Failed to upload proof of repair image: " + e.getMessage());
        }

        if (repairRemarks != null) report.setRepairRemarks(repairRemarks);
        report.setStatus(ReportStatus.COMPLETED.getLabel());

        LocalDateTime completionTime = LocalDateTime.now(ZoneId.of("Asia/Manila"));
        report.setDateArchived(completionTime);
        report.setActualCompletionDate(completionTime.toLocalDate());
        repository.save(report);

        sendStatusUpdateEmail(report, ReportStatus.COMPLETED.getLabel(), repairRemarks);

        reportStatusLogService.log(
                report,
                "COMPLETED",
                previousStatus,
                ReportStatus.COMPLETED.getLabel(),
                repairRemarks,
                currentUser,
                currentUser.getFirstName() + " " + currentUser.getLastName(),
                "ENGINEER",
                report.getProofOfRepairImage()
        );

        activityLogService.log(
                currentUser,
                "PROJECT",
                "REPAIR_COMPLETED",
                "#PRJ-" + String.format("%04d", report.getId()),
                "CEO marked project as Completed and submitted proof of repair.",
                "SUCCESS",
                request
        );

        liveUpdatePublisher.publish(barangaysOf(report));
        return ResponseEntity.ok().body(Map.of("message", "Project marked as Completed!"));
    }

    // ==========================================
    // 7. CEO DEFER REPORT
    // ==========================================
    @PutMapping("/{id}/defer")
    @PreAuthorize("hasAnyRole('ENGINEER', 'City Engineer', 'ADMIN', 'CPDO Admin')")
    @Transactional
    @CacheEvict(value = "dashboardStats", allEntries = true)
    public ResponseEntity<?> deferReport(
            @PathVariable Long id,
            @RequestBody Map<String, String> payload,
            HttpServletRequest request) {

        User currentUser = getAuthenticatedUser();
        RoadReport report = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Report not found"));

        String previousStatus = report.getStatus();
        reportWorkflowService.validateTransition(ReportStatus.fromString(previousStatus), ReportStatus.PENDING_BUDGET);

        String reason = payload != null ? payload.get("repairRemarks") : null;
        if (reason == null || reason.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Deferral reason is required."));
        }

        report.setRepairRemarks(reason.trim());
        report.setStatus(ReportStatus.PENDING_BUDGET.getLabel());
        repository.save(report);

        sendStatusUpdateEmail(report, ReportStatus.PENDING_BUDGET.getLabel(), reason);

        reportStatusLogService.log(
                report,
                "PENDING_BUDGET",
                previousStatus,
                ReportStatus.PENDING_BUDGET.getLabel(),
                reason,
                currentUser,
                currentUser.getFirstName() + " " + currentUser.getLastName(),
                "ENGINEER",
                null
        );

        activityLogService.log(
                currentUser,
                "PROJECT",
                "REPAIR_DEFERRED",
                "#PRJ-" + String.format("%04d", report.getId()),
                "Repair deferred by CEO to Pending Budget. Reason: " + reason,
                "SUCCESS",
                request
        );

        liveUpdatePublisher.publish(barangaysOf(report));
        return ResponseEntity.ok().body(Map.of("message", "Project marked as Pending Budget. CPDO Admin notified."));
    }

    // ==========================================
    // 8. ADMIN DEADLINE REMINDER
    // ==========================================
    @PostMapping("/{id}/remind-due")
    @PreAuthorize("hasAnyRole('ADMIN', 'CPDO Admin')")
    @Transactional
    public ResponseEntity<?> sendDeadlineReminder(
            @PathVariable Long id,
            HttpServletRequest request) {

        User currentUser = getAuthenticatedUser();
        RoadReport report = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Report not found"));

        if (report.getTargetCompletionDate() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "This project has no scheduled target completion date."));
        }


        // Anti-spam cooldown: the column was written but never read, so an admin
        // could re-issue reminders indefinitely. One per hour per report.
        LocalDateTime nowPst = LocalDateTime.now(ZoneId.of("Asia/Manila"));
        if (report.getLastReminderSent() != null
                && report.getLastReminderSent().isAfter(nowPst.minusHours(1))) {
            return ResponseEntity.status(429).body(Map.of("error",
                    "A deadline reminder was already sent recently. Please wait at least an hour after sending another."));
        }
        // A reminder must not report success (or stamp lastReminderSent) when
        // there is no engineer account to receive it.
        Long engineerId = getCeoId();
        if (engineerId == null) {
            return ResponseEntity.status(409).body(Map.of("error",
                    "No City Engineer account is available to receive deadline reminders."));
        }

        report.setLastReminderSent(nowPst);
        repository.save(report);

        notificationService.sendNotification(
                engineerId,
                "⏰ Deadline Expedite Reminder",
                "CPDO Admin issued an expedite reminder for report PRJ-" + report.getId() +
                        " (" + report.getCityRoadName() + "). Scheduled target date: " + report.getTargetCompletionDate() + ".",
                "REMINDER",
                "REPORT", report.getId()
        );

        reportStatusLogService.log(
                report,
                "DEADLINE_REMINDER_ISSUED",
                report.getStatus(),
                report.getStatus(),
                "CPDO Admin issued an expedite/deadline reminder to the City Engineer.",
                currentUser,
                currentUser.getFirstName() + " " + currentUser.getLastName(),
                "ADMIN",
                null
        );

        activityLogService.log(
                currentUser,
                "PROJECT",
                "DEADLINE_REMINDER_SENT",
                "#PRJ-" + String.format("%04d", report.getId()),
                "Admin sent deadline reminder for " + report.getCityRoadName() + ".",
                "SUCCESS",
                request
        );

        liveUpdatePublisher.publish(barangaysOf(report));
        return ResponseEntity.ok(Map.of("message", "Deadline reminder successfully issued.", "lastReminderSent", nowPst.toString()));
    }

    // ==========================================
    // 9. CEO TIME EXTENSION REQUEST
    // ==========================================
    @PostMapping("/{id}/request-extension")
    @PreAuthorize("hasAnyRole('ENGINEER', 'City Engineer', 'ADMIN', 'CPDO Admin')")
    @Transactional
    public ResponseEntity<?> requestExtension(
            @PathVariable Long id,
            @RequestBody Map<String, Object> payload,
            HttpServletRequest request) {

        User currentUser = getAuthenticatedUser();
        RoadReport report = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Report not found"));

        String targetDateStr = (String) payload.get("extensionTargetDate");
        String reason = (String) payload.get("extensionReason");

        if (targetDateStr == null || targetDateStr.trim().isEmpty() || reason == null || reason.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Proposed date and reason are required."));
        }

        LocalDate proposedDate;
        try {
            proposedDate = LocalDate.parse(targetDateStr.trim());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid extension target date format. Expected YYYY-MM-DD."));
        }

        if (proposedDate.isBefore(LocalDate.now())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Proposed extension date cannot be in the past."));
        }

        report.setExtensionTargetDate(proposedDate);
        report.setExtensionReason(reason.trim());
        report.setExtensionStatus("PENDING");
        repository.save(report);

        reportStatusLogService.log(
                report,
                "EXTENSION_REQUESTED",
                report.getStatus(),
                report.getStatus(),
                "CEO filed extension request to " + proposedDate + ". Reason: " + reason,
                currentUser,
                currentUser.getFirstName() + " " + currentUser.getLastName(),
                "ENGINEER",
                null
        );

        activityLogService.log(
                currentUser,
                "PROJECT",
                "EXTENSION_REQUESTED",
                "#PRJ-" + String.format("%04d", report.getId()),
                "CEO requested extension to " + proposedDate,
                "SUCCESS",
                request
        );

        liveUpdatePublisher.publish(barangaysOf(report));
        return ResponseEntity.ok(Map.of("message", "Extension request submitted.", "extensionStatus", "PENDING"));
    }

    // ==========================================
    // 10. ADMIN REVIEW EXTENSION REQUEST
    // ==========================================
    @PutMapping("/{id}/review-extension")
    @PreAuthorize("hasAnyRole('ADMIN', 'CPDO Admin')")
    @Transactional
    public ResponseEntity<?> reviewExtension(
            @PathVariable Long id,
            @RequestBody Map<String, Object> payload,
            HttpServletRequest request) {

        User currentUser = getAuthenticatedUser();
        RoadReport report = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Report not found"));

        String decision = (String) payload.get("decision");
        String adminRemarks = (String) payload.get("adminRemarks");

        if (decision == null || (!decision.equalsIgnoreCase("APPROVED") && !decision.equalsIgnoreCase("REJECTED"))) {
            return ResponseEntity.badRequest().body(Map.of("error", "Decision must be either APPROVED or REJECTED."));
        }

        if (!"PENDING".equalsIgnoreCase(report.getExtensionStatus())) {
            return ResponseEntity.badRequest().body(Map.of("error", "This report has no pending extension request to review."));
        }

        boolean isApproved = "APPROVED".equalsIgnoreCase(decision);
        if (isApproved) {
            if (report.getExtensionTargetDate() == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "Extension request target date is missing."));
            }
            report.setTargetCompletionDate(report.getExtensionTargetDate());
            report.setExtensionStatus("APPROVED");
        } else {
            report.setExtensionStatus("REJECTED");
        }

        repository.save(report);

        reportStatusLogService.log(
                report,
                isApproved ? "EXTENSION_APPROVED" : "EXTENSION_REJECTED",
                report.getStatus(),
                report.getStatus(),
                "Admin " + (isApproved ? "approved" : "rejected") + " extension request.",
                currentUser,
                currentUser.getFirstName() + " " + currentUser.getLastName(),
                "ADMIN",
                null
        );

        activityLogService.log(
                currentUser,
                "QA",
                isApproved ? "EXTENSION_APPROVED" : "EXTENSION_REJECTED",
                "#PRJ-" + String.format("%04d", report.getId()),
                "Admin reviewed extension request.",
                "SUCCESS",
                request
        );

        liveUpdatePublisher.publish(barangaysOf(report));
        return ResponseEntity.ok(Map.of("message", "Extension request " + (isApproved ? "approved" : "rejected") + "."));
    }

    // ==========================================
    // 11. BATCH ARCHIVE (ADMIN)
    // ==========================================
    @PostMapping("/batch/archive")
    @PreAuthorize("hasAnyRole('ADMIN', 'CPDO Admin')")
    @Transactional
    @CacheEvict(value = "dashboardStats", allEntries = true)
    public ResponseEntity<?> batchArchiveReports(@RequestBody Map<String, Object> payload, HttpServletRequest request) {
        User currentUser = getAuthenticatedUser();
        Object idsObj = payload.get("reportIds");
        if (idsObj == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "No reports selected for archiving."));
        }

        List<Long> reportIds = new ArrayList<>();
        if (idsObj instanceof List) {
            for (Object id : (List<?>) idsObj) {
                reportIds.add(Long.valueOf(id.toString()));
            }
        }

        List<RoadReport> reportsToArchive = repository.findAllById(reportIds);
        int archivedCount = 0;

        for (RoadReport r : reportsToArchive) {
            if ("Pending Budget".equalsIgnoreCase(r.getStatus())) {
                String prev = r.getStatus();
                r.setStatus(ReportStatus.ARCHIVED.getLabel());
                r.setDateArchived(LocalDateTime.now(ZoneId.of("Asia/Manila")));
                archivedCount++;

                reportStatusLogService.log(
                        r,
                        "ARCHIVED",
                        prev,
                        ReportStatus.ARCHIVED.getLabel(),
                        "Archived during CPDO batch housekeeping.",
                        currentUser,
                        currentUser.getFirstName() + " " + currentUser.getLastName(),
                        "ADMIN",
                        null
                );
            }
        }

        repository.saveAll(reportsToArchive);

        activityLogService.log(
                currentUser,
                "PROJECT",
                "BATCH_ARCHIVED",
                "ARCHIVE_POOL",
                "CPDO Admin archived " + archivedCount + " deferred project reports.",
                "SUCCESS",
                request
        );

        liveUpdatePublisher.publish(barangaysOf(reportsToArchive));
        return ResponseEntity.ok().body(Map.of("message", "Successfully archived " + archivedCount + " deferred reports."));
    }

    // ==========================================
    // 12. ANNUAL ROLLOVER
    // ==========================================
    @PostMapping("/rollover-annual-cycle")
    @PreAuthorize("hasAnyRole('ADMIN', 'CPDO Admin')")
    @Transactional
    @CacheEvict(value = "dashboardStats", allEntries = true)
    public ResponseEntity<?> executeAnnualRollover(HttpServletRequest request) {
        User currentUser = getAuthenticatedUser();

        // Archive each report through the normal entity lifecycle so the
        // archival timestamp, a timeline entry and the @Version increment are
        // all persisted (the previous bulk UPDATE silently skipped all three).
        List<RoadReport> reportsToArchive = repository.findAnnualCycleArchivableReports();
        LocalDateTime nowPst = LocalDateTime.now(ZoneId.of("Asia/Manila"));
        for (RoadReport report : reportsToArchive) {
            String previousStatus = report.getStatus();
            report.setStatus(ReportStatus.ARCHIVED.getLabel());
            report.setDateArchived(nowPst);

            reportStatusLogService.log(
                    report,
                    "ARCHIVED",
                    previousStatus,
                    ReportStatus.ARCHIVED.getLabel(),
                    "Archived by the annual rollover cycle.",
                    currentUser,
                    currentUser.getFirstName() + " " + currentUser.getLastName(),
                    "ADMIN",
                    null
            );
        }
        repository.saveAll(reportsToArchive);
        int archivedCount = reportsToArchive.size();

        activityLogService.log(
                currentUser,
                "SYSTEM",
                "ANNUAL_ROLLOVER_EXECUTED",
                "ANNUAL_CYCLE",
                "CPDO Admin initialized annual rollover cycle. Archived " + archivedCount + " reports.",
                "SUCCESS",
                request
        );

        liveUpdatePublisher.publish(barangaysOf(reportsToArchive));
        return ResponseEntity.ok(Map.of("message", "Successfully archived " + archivedCount + " reports.", "archivedCount", archivedCount));
    }

    // ==========================================
    // TIMELINE AUDIT TRAIL
    // ==========================================
    @GetMapping("/{id}/timeline")
    public ResponseEntity<List<ReportStatusLog>> getReportTimeline(@PathVariable Long id) {
        User currentUser = getAuthenticatedUser();
        RoadReport report = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Report not found"));
        // The timeline carries remarks, actor identities and attachment URLs, so
        // it gets the same barangay ownership check as the report itself.
        enforceBarangayReportAccess(currentUser, report);
        return ResponseEntity.ok(reportStatusLogService.getTimeline(id));
    }

    private void sendStatusUpdateEmail(RoadReport report, String newStatus, String remarks) {
        if (report.getUser() == null || report.getUser().getEmail() == null || report.getUser().getEmail().isEmpty()) {
            return;
        }

        String officialEmail = report.getUser().getEmail();
        String officialName = report.getUser().getFirstName();
        String trackingId = "RPT-" + report.getId();
        String subject = "RoadWise Update: Status changed to " + newStatus;
        StringBuilder body = new StringBuilder();

        body.append("Hello ").append(officialName).append(",\n\n");
        body.append("There is an update regarding your road report [").append(trackingId).append("] for ").append(report.getCityRoadName()).append(".\n\n");
        body.append("Current Status: ").append(newStatus.toUpperCase()).append("\n\n");

        if (remarks != null && !remarks.trim().isEmpty()) {
            body.append("\nRemarks: ").append(remarks).append("\n");
        }

        body.append("\nPlease log into your RoadWise Dashboard to view full details.\n\n");
        body.append("Best regards,\nRoadWise SJDM System");

        try {
            emailService.sendEmail(officialEmail, subject, body.toString());
        } catch (Exception ignored) {}
    }

    /**
     * The barangays a change touched, for {@link LiveUpdatePublisher}. Passing the affected
     * barangays rather than a global broadcast is what keeps a report filed in one barangay
     * from refetching every dashboard in the city.
     */
    /**
     * Runs a side effect only after the surrounding transaction commits.
     *
     * <p>Notifications and SSE fan-out talk to the outside world and cannot be rolled back. Firing
     * them inside the transaction means a later failure returns an error to a user whose report is
     * already saved, and the retry files a duplicate. Failures inside the callback are swallowed and
     * logged rather than propagated: by the time it runs the report has committed, so surfacing an
     * error would recreate the exact retry-into-a-duplicate problem this guards against.</p>
     */
    private void registerPostCommit(Runnable action) {
        Runnable guarded = () -> {
            try {
                action.run();
            } catch (Exception e) {
                System.err.println("⚠️ [POST-COMMIT] side effect failed after report commit: " + e.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    guarded.run();
                }
            });
        } else {
            guarded.run();
        }
    }

    private static List<Long> barangaysOf(RoadReport report) {
        return report.getBarangay() != null ? List.of(report.getBarangay().getId()) : List.of();
    }

    private static List<Long> barangaysOf(Collection<RoadReport> reports) {
        return reports.stream()
                .map(RoadReport::getBarangay)
                .filter(Objects::nonNull)
                .map(Barangay::getId)
                .toList();
    }

    @GetMapping("/image/{filename:.+}")
    public ResponseEntity<Resource> serveImage(@PathVariable String filename) {
        try {
            if (filename == null || filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
                return ResponseEntity.badRequest().build();
            }
            Path uploadsDir = Paths.get("uploads").toAbsolutePath().normalize();
            Path file = uploadsDir.resolve(filename).toAbsolutePath().normalize();
            if (!file.startsWith(uploadsDir)) {
                return ResponseEntity.badRequest().build();
            }
            Resource resource = new UrlResource(file.toUri());
            if (resource.exists() || resource.isReadable()) {
                String contentType = Files.probeContentType(file);
                if (contentType == null) contentType = "application/octet-stream";
                return ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, contentType).body(resource);
            } else {
                return ResponseEntity.notFound().build();
            }
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }
}

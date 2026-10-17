package com.roadwise.backend.service;

import com.roadwise.backend.dto.BarangayReportCreateDto;
import com.roadwise.backend.model.CityRoad;
import com.roadwise.backend.model.DefectCategory;
import com.roadwise.backend.model.ImagePerspective;
import com.roadwise.backend.model.ReportImage;
import com.roadwise.backend.model.ReportStatus;
import com.roadwise.backend.model.RoadReport;
import com.roadwise.backend.model.User;
import com.roadwise.backend.repository.CityRoadRepository;
import com.roadwise.backend.repository.RoadReportRepository;
import com.roadwise.backend.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * The barangay inspector's 3-field submission: photos, a GPS pin, a category.
 *
 * <p>Separate from {@code RoadReportController.createReport} on purpose. That method
 * is a 130-line handler that accepts the full engineering form — length, width,
 * culverts, bridges — because it predates the role split. This path takes
 * {@link BarangayReportCreateDto}, which has no field for any of those, so the
 * decoupling is enforced by the type system rather than by a runtime check that a
 * future edit could quietly drop.</p>
 *
 * <p>Ordering is the safety property that matters: inference and the Stage-1 gate
 * run <em>before</em> anything is persisted or uploaded to the public bucket, so a
 * rejected upload leaves no row, no orphan image, and no notification. The legacy
 * path quarantines and persists; this path refuses outright, which is what the
 * guardrail is for.</p>
 */
@Service
public class FieldReportSubmissionService {

    private final RoadReportRepository reportRepository;
    private final CityRoadRepository cityRoadRepository;
    private final SupabaseStorageService storageService;
    private final DuplicateDetectionService duplicateDetectionService;
    private final NotificationService notificationService;
    private final ReportStatusLogService statusLogService;
    private final RoadInspectionService aiService;
    private final UserRepository userRepository;

    public FieldReportSubmissionService(RoadReportRepository reportRepository,
                                        CityRoadRepository cityRoadRepository,
                                        SupabaseStorageService storageService,
                                        DuplicateDetectionService duplicateDetectionService,
                                        NotificationService notificationService,
                                        ReportStatusLogService statusLogService,
                                        RoadInspectionService aiService,
                                        UserRepository userRepository) {
        this.reportRepository = reportRepository;
        this.cityRoadRepository = cityRoadRepository;
        this.storageService = storageService;
        this.duplicateDetectionService = duplicateDetectionService;
        this.notificationService = notificationService;
        this.statusLogService = statusLogService;
        this.aiService = aiService;
        this.userRepository = userRepository;
    }

    /**
     * Either a persisted report or the list of photos Stage 1 refused — never both.
     * A rejection carries no report id because nothing was written.
     */
    public static final class SubmissionResult {

        private final RoadReport report;
        private final List<RoadInspectionService.RejectedImage> rejected;

        private SubmissionResult(RoadReport report, List<RoadInspectionService.RejectedImage> rejected) {
            this.report = report;
            this.rejected = rejected == null ? Collections.emptyList() : List.copyOf(rejected);
        }

        static SubmissionResult saved(RoadReport report) {
            return new SubmissionResult(report, Collections.emptyList());
        }

        static SubmissionResult rejected(List<RoadInspectionService.RejectedImage> rejected) {
            return new SubmissionResult(null, rejected);
        }

        public boolean isRejected() { return !rejected.isEmpty(); }
        public RoadReport getReport() { return report; }
        public List<RoadInspectionService.RejectedImage> getRejected() { return rejected; }
    }

    /**
     * Validate, infer, and persist one field report.
     *
     * @throws IllegalArgumentException when the defect category is not one of the
     *         four supported values — the controller maps this to HTTP 400.
     */
    @Transactional
    public SubmissionResult submit(BarangayReportCreateDto dto,
                                   List<MultipartFile> images,
                                   List<String> perspectives,
                                   User currentUser) {

        DefectCategory category = DefectCategory.parseStrict(dto.getDamageType());
        List<MultipartFile> photos = usablePhotos(images);

        // ---- Stage 1 + 2, before any persistence ----
        RoadInspectionService.MultiImageInspectionResult inference = null;
        if (!photos.isEmpty()) {
            inference = aiService.analyzeMultiImage(photos, perspectives);
            if (inference.isValidationRejection()) {
                return SubmissionResult.rejected(inference.getRejected());
            }
        }
        // A transport failure is not a rejection: the officer keeps their report,
        // marked Unassessed for manual review. That mirrors the legacy behaviour.
        boolean publishable = inference != null && inference.isAccepted();

        RoadReport report = new RoadReport();
        report.setDamageType(category.label());
        report.setDamageDescription(dto.getDamageDescription());
        report.setLatitude(dto.getLatitude());
        report.setLongitude(dto.getLongitude());
        report.setCityRoadName(dto.getCityRoadName());
        report.setUser(currentUser);
        report.setReportedBy(displayName(currentUser));
        report.setBarangay(currentUser.getBarangay());
        // Engineering fields are deliberately left null: this form cannot supply them.

        applyRoadSnapshot(report, dto.getCityRoadId());

        if (publishable) {
            report.setSeverity(inference.getAggregatedSeverity());
            report.setCvSeverityScore(inference.getAggregatedSeverityScore());
            report.setCvConfidenceScore(inference.getConfidence());
        } else {
            report.setSeverity("Unassessed");
            report.setCvConfidenceScore(0.0);
        }

        report.setStatus(ReportStatus.PENDING_VALIDATION.getLabel());

        // Attach evidence before the single persist so the report and its images are
        // written in one transaction; re-saving a detached report would re-proxy its
        // lazy associations (the reason the legacy path also persists once).
        boolean anyStored = false;
        for (int index = 0; index < photos.size(); index++) {
            MultipartFile photo = photos.get(index);
            String url = store(photo, publishable);
            if (url != null) {
                anyStored = true;
            }
            ReportImage image = new ReportImage(report, url != null ? url : "upload_failed.jpg",
                    perspectiveAt(perspectives, index));
            image.setGatePassed(publishable);
            if (publishable) {
                image.setCvSeverityScore(inference.getAggregatedSeverityScore());
                image.setCvConfidenceScore(inference.getConfidence());
            }
            report.addImage(image);
        }
        report.setDamageImage(primaryImageUrl(report));

        Optional<RoadReport> duplicate = duplicateDetectionService.findPotentialDuplicate(report);
        duplicate.ifPresent(existing -> {
            report.setPotentialDuplicate(true);
            report.setDuplicateOfReportId(existing.getId());
        });

        RoadReport saved = reportRepository.save(report);

        statusLogService.log(
                saved,
                "SUBMITTED",
                null,
                ReportStatus.PENDING_VALIDATION.getLabel(),
                "Field report submitted for '" + safeName(saved) + "'.",
                currentUser,
                saved.getReportedBy(),
                "Barangay Official",
                saved.getDamageImage());

        notifyCdo(saved, duplicate);

        System.out.println(">>> [FIELD REPORT] PRJ-" + saved.getId()
                + " category=" + category.label()
                + " images=" + saved.getImages().size()
                + " severity=" + saved.getSeverity()
                + (anyStored ? "" : " (no image stored)"));
        return SubmissionResult.saved(saved);
    }

    // ---------------------------------------------------------------- helpers

    /** Drops nulls and empty parts, and enforces the 3-photo ceiling. */
    private static List<MultipartFile> usablePhotos(List<MultipartFile> images) {
        if (images == null || images.isEmpty()) {
            return Collections.emptyList();
        }
        List<MultipartFile> usable = new ArrayList<>();
        for (MultipartFile image : images) {
            if (image != null && !image.isEmpty() && usable.size() < RoadInspectionService.MAX_REPORT_IMAGES) {
                usable.add(image);
            }
        }
        return usable;
    }

    private static ImagePerspective perspectiveAt(List<String> perspectives, int index) {
        String raw = perspectives != null && index < perspectives.size() ? perspectives.get(index) : null;
        return ImagePerspective.fromString(raw);
    }

    /**
     * Store a photo in the public bucket only when the cascade accepted it;
     * everything else goes to quarantine.
     *
     * @return the stored URL, or null when storage failed (caller substitutes a
     *         placeholder so the report is still filed).
     */
    private String store(MultipartFile photo, boolean publishable) {
        try {
            return publishable
                    ? storageService.uploadImage(photo)
                    : storageService.uploadImageQuarantined(photo);
        } catch (Exception e) {
            System.err.println(">>> [FIELD REPORT] image upload failed: " + e.getMessage());
            return null;
        }
    }

    private static String primaryImageUrl(RoadReport report) {
        if (report.getImages().isEmpty()) {
            return "no_image.jpg";
        }
        // Legacy column stays populated so existing list/tracker screens keep working.
        return report.getImages().get(0).getImageUrl();
    }

    /**
     * Copy the road's snapshot fields onto the report. Accepts either the numeric id
     * or the human-readable road code, matching the legacy resolver.
     */
    private void applyRoadSnapshot(RoadReport report, String cityRoadId) {
        if (cityRoadId == null || cityRoadId.trim().isEmpty()) {
            return;
        }
        String token = cityRoadId.trim();
        CityRoad road = token.chars().allMatch(Character::isDigit)
                ? cityRoadRepository.findById(Long.valueOf(token)).orElse(null)
                : cityRoadRepository.findByRoadId(token).orElse(null);
        if (road == null) {
            return;
        }
        report.setCityRoadFkId(road.getId());
        report.setCityRoadId(road.getRoadId());
        if (road.getRoadName() != null && !road.getRoadName().trim().isEmpty()) {
            report.setCityRoadName(road.getRoadName());
        }
        report.setRoadImportance(road.getRoadImportance());
        report.setRoadType(road.getRoadType());
        report.setTerrainType(road.getTerrainType());
    }

    private static String displayName(User user) {
        if (user == null) {
            return "Unknown reporter";
        }
        String first = user.getFirstName() == null ? "" : user.getFirstName().trim();
        String last = user.getLastName() == null ? "" : user.getLastName().trim();
        String combined = (first + " " + last).trim();
        return combined.isEmpty() ? user.getUsername() : combined;
    }

    private static String safeName(RoadReport report) {
        return report.getCityRoadName() != null && !report.getCityRoadName().isBlank()
                ? report.getCityRoadName()
                : "unlinked location";
    }

    /** Notifications are external effects: announce only once the report commits. */
    private void notifyCdo(RoadReport report, Optional<RoadReport> duplicate) {
        Long adminId = userRepository.findFirstByRoleInIgnoreCase(List.of("cpdo admin", "admin"))
                .map(User::getId)
                .orElse(null);
        if (adminId == null) {
            return;
        }
        final Long reportId = report.getId();
        final String roadName = safeName(report);
        final String reportedBy = report.getReportedBy();
        final String severity = report.getSeverity();
        final Long duplicateOf = duplicate.map(RoadReport::getId).orElse(null);

        afterCommit(() -> {
            if (severity != null && severity.equalsIgnoreCase("High")) {
                notificationService.sendNotification(adminId,
                        "🚨 CRITICAL HAZARD DETECTED",
                        "System flagged a HIGH severity road damage reported by " + reportedBy
                                + " on " + roadName + ". Immediate CPDO review required!",
                        "CRITICAL");
            } else {
                notificationService.sendNotification(adminId,
                        "New Field Report Submitted",
                        "A new road damage report (PRJ-" + reportId + ") was submitted by "
                                + reportedBy + " for " + roadName + ".",
                        "REPORT_SUBMITTED");
            }
            if (duplicateOf != null) {
                notificationService.sendNotification(adminId,
                        "⚠️ Potential Duplicate Report Flagged",
                        "Report PRJ-" + reportId + " on " + roadName
                                + " was flagged as a potential duplicate of PRJ-" + duplicateOf + ".",
                        "DUPLICATE_WARNING");
            }
        });
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}

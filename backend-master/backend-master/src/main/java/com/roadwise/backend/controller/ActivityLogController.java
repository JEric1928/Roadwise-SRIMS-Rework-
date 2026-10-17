package com.roadwise.backend.controller;

import com.roadwise.backend.dto.PageResponseDto;
import com.roadwise.backend.model.ActivityLog;
import com.roadwise.backend.model.User;
import com.roadwise.backend.repository.ActivityLogRepository;
import com.roadwise.backend.repository.UserRepository;
import com.roadwise.backend.service.ActivityLogService;
import com.roadwise.backend.util.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/activity-logs")
public class ActivityLogController {

    @Autowired
    private ActivityLogRepository activityLogRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ActivityLogService activityLogService;

    // 1. GET ACTIVITY LOGS (newest first, paged)
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'CPDO Admin')")
    public PageResponseDto<ActivityLog> getAllLogs(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "100") int size) {

        // Cap the page size: an uncapped size is the unbounded read this replaces.
        int cappedSize = Math.min(Math.max(1, size), 200);
        // Stable tie-breaker on id keeps paging consistent when timestamps repeat.
        Pageable pageable = PageRequest.of(Math.max(0, page), cappedSize,
                Sort.by(Sort.Direction.DESC, "timestamp").and(Sort.by(Sort.Direction.DESC, "id")));

        Page<ActivityLog> logPage = activityLogRepository.findAll(pageable);

        return new PageResponseDto<>(
                logPage.getContent(),
                logPage.getNumber(),
                logPage.getSize(),
                logPage.getTotalElements(),
                logPage.getTotalPages(),
                logPage.isLast()
        );
    }

    // 2. GET SINGLE LOG BY ID (For Audit Inspector Modal)
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'CPDO Admin')")
    public ResponseEntity<ActivityLog> getLogById(@PathVariable Long id) {
        return activityLogRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    private static final java.util.Set<String> ALLOWED_CLIENT_CATEGORIES = java.util.Set.of("EXPORT", "PRINT", "UI_ACTION");

    // 3. LOG FRONTEND EXPORT / PRINT ACTIONS (Audit Compliance)
    @PostMapping("/log-action")
    public ResponseEntity<?> recordClientAction(@RequestBody Map<String, Object> payload, HttpServletRequest request) {
        try {
            // Identity is derived from the authenticated principal (JWT). Any
            // client-supplied "userId" in the payload is intentionally ignored,
            // because a caller could otherwise impersonate another user in the audit trail.
            String category = (String) payload.getOrDefault("category", "EXPORT");
            String action = (String) payload.getOrDefault("action", "EXPORT_AUDIT_LOG");
            String targetEntity = (String) payload.getOrDefault("targetEntity", "SYSTEM");
            String description = (String) payload.getOrDefault("description", "User performed export action.");

            category = category != null ? category.trim().toUpperCase() : "EXPORT";
            action = action != null ? action.trim().toUpperCase() : "EXPORT_AUDIT_LOG";
            targetEntity = targetEntity != null ? targetEntity.trim() : "SYSTEM";
            description = description != null ? description.trim() : "";

            if (!ALLOWED_CLIENT_CATEGORIES.contains(category)) {
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid activity log category."));
            }

            if (!(action.startsWith("EXPORT_") || action.startsWith("PRINT_") || action.startsWith("UI_"))) {
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid client activity log action."));
            }

            if (category.length() > 20 || action.length() > 50 || targetEntity.length() > 50) {
                return ResponseEntity.badRequest().body(Map.of("error", "Action metadata exceeds allowed length limit."));
            }

            if (description.length() > 255) {
                description = description.substring(0, 255);
            }

            User actor = SecurityUtils.getCurrentUser();

            ActivityLog savedLog = activityLogService.log(actor, category, action, targetEntity, description, "SUCCESS", request);
            return ResponseEntity.ok(savedLog);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "Failed to record activity log."));
        }
    }
}
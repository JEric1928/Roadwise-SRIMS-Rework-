package com.roadwise.backend.controller;

import com.roadwise.backend.model.ActivityLog;
import com.roadwise.backend.model.User;
import com.roadwise.backend.repository.ActivityLogRepository;
import com.roadwise.backend.service.ActivityLogService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/activity-logs")
@CrossOrigin(origins = "${frontend.url}")
public class ActivityLogController {
    private final ActivityLogRepository activityLogRepository;
    private final ActivityLogService activityLogService;

    public ActivityLogController(ActivityLogRepository activityLogRepository, ActivityLogService activityLogService) {
        this.activityLogRepository = activityLogRepository;
        this.activityLogService = activityLogService;
    }

    private User getAuthenticatedUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof User user) {
            return user;
        }
        throw new AccessDeniedException("User is not authenticated.");
    }

    // 1. GET ALL ACTIVITY LOGS (Ordered by Newest First)
    @GetMapping
    public List<ActivityLog> getAllLogs() {
        return activityLogRepository.findAllByOrderByTimestampDesc();
    }

    // 2. GET SINGLE LOG BY ID (For Audit Inspector Modal)
    @GetMapping("/{id}")
    public ResponseEntity<ActivityLog> getLogById(@PathVariable Long id) {
        return activityLogRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // 3. LOG FRONTEND EXPORT / PRINT ACTIONS (Audit Compliance)
    @PostMapping("/log-action")
    public ResponseEntity<?> recordClientAction(@RequestBody Map<String, Object> payload, HttpServletRequest request) {
        try {
            String category = (String) payload.getOrDefault("category", "EXPORT");
            String action = (String) payload.getOrDefault("action", "EXPORT_AUDIT_LOG");
            String targetEntity = (String) payload.getOrDefault("targetEntity", "SYSTEM");
            String description = (String) payload.getOrDefault("description", "User performed export action.");
            User actor = getAuthenticatedUser();
            ActivityLog savedLog = activityLogService.log(actor, category, action, targetEntity, description, "SUCCESS", request);
            return ResponseEntity.ok(savedLog);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "Failed to record activity log: " + e.getMessage()));
        }
    }
}
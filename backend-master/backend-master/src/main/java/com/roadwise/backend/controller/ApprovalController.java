package com.roadwise.backend.controller;

import com.roadwise.backend.model.PrivilegedActionRequest;
import com.roadwise.backend.service.ApprovalService;
import com.roadwise.backend.util.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maker-checker ticket handling for privileged actions.
 *
 * <p>A maker posts the action they need on a peer account and why. A different administrator
 * approves it here, and only then may the maker call the original endpoint once with
 * {@code X-Approval-Id}. The action endpoints themselves are unchanged -- refusing the
 * rank check would have reopened the seizure path, so the approval is an extra header, not a
 * weaker rule.
 *
 * <p>Errors are 400 with a readable message rather than 500: every rejection here is an
 * expected outcome of a policy (self-approval, expired ticket, no such account), and the
 * administrator on the other end needs to know which one it was.
 */
@RestController
@RequestMapping("/api/admin/approvals")
public class ApprovalController {

    private final ApprovalService approvals;

    public ApprovalController(ApprovalService approvals) {
        this.approvals = approvals;
    }

    /** Opens (or reuses) a request. Body: {@code {permission, targetUserId, reason}}. */
    @PostMapping
    public ResponseEntity<?> request(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        try {
            PrivilegedActionRequest row = approvals.request(
                    text(body.get("permission")),
                    asLong(body.get("targetUserId")),
                    text(body.get("reason")),
                    request);
            return ResponseEntity.ok(view(row));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    /**
     * Every pending request plus the caller's own, so one screen serves both the approver's
     * inbox and the maker's "did anyone look at this yet".
     */
    @GetMapping
    @PreAuthorize("@authz.has('user:approve')")
    public List<Map<String, Object>> overview(@RequestParam(defaultValue = "false") boolean mine) {
        Long callerId = SecurityUtils.getCurrentUserId();
        return approvals.overview(callerId, mine).stream().map(ApprovalController::view).toList();
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("@authz.has('user:approve')")
    public ResponseEntity<?> approve(@PathVariable Long id,
                                     @RequestBody(required = false) Map<String, Object> body,
                                     HttpServletRequest request) {
        return decide(id, true, body, request);
    }

    @PostMapping("/{id}/deny")
    @PreAuthorize("@authz.has('user:approve')")
    public ResponseEntity<?> deny(@PathVariable Long id,
                                  @RequestBody(required = false) Map<String, Object> body,
                                  HttpServletRequest request) {
        return decide(id, false, body, request);
    }

    private ResponseEntity<?> decide(Long id, boolean approve,
                                     Map<String, Object> body, HttpServletRequest request) {
        try {
            return ResponseEntity.ok(view(approvals.decide(id, approve,
                    body == null ? null : text(body.get("note")), request)));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    private static Map<String, Object> view(PrivilegedActionRequest row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", row.getId());
        view.put("permission", row.getPermissionKey());
        view.put("targetUserId", row.getTargetUserId());
        view.put("requestedBy", row.getRequestedBy());
        view.put("reason", row.getReason());
        view.put("status", row.getStatus());
        view.put("requestedAt", row.getRequestedAt());
        view.put("expiresAt", row.getExpiresAt());
        view.put("decidedBy", row.getDecidedBy());
        view.put("decidedAt", row.getDecidedAt());
        view.put("decisionNote", row.getDecisionNote());
        view.put("executedAt", row.getExecutedAt());
        return view;
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.valueOf(value.toString().trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}

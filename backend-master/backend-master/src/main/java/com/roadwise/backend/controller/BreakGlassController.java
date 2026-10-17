package com.roadwise.backend.controller;

import com.roadwise.backend.model.BreakGlassGrant;
import com.roadwise.backend.service.BreakGlassService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Time-boxed emergency access to a peer account.
 *
 * <p>Activation is self-service on purpose -- the case it exists for is the one where no
 * second administrator is available to approve. What keeps that honest is not a signature but
 * the constraints: an hour at most, a written reason, every other administrator notified
 * immediately, and a row that stays behind for review.
 */
@RestController
@RequestMapping("/api/admin/break-glass")
public class BreakGlassController {

    private final BreakGlassService breakGlass;

    public BreakGlassController(BreakGlassService breakGlass) {
        this.breakGlass = breakGlass;
    }

    /** Body: {@code {reason, minutes}}. */
    @PostMapping
    @PreAuthorize("@authz.has('user:break-glass')")
    public ResponseEntity<?> activate(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        try {
            return ResponseEntity.ok(view(breakGlass.activate(
                    body == null ? null : text(body.get("reason")),
                    body == null ? null : asInt(body.get("minutes")),
                    request)));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    /** Recent grants, newest first, so an administrator can see what still needs review. */
    @GetMapping
    @PreAuthorize("@authz.has('user:approve')")
    public List<Map<String, Object>> overview() {
        return breakGlass.overview().stream().map(BreakGlassController::view).toList();
    }

    @PostMapping("/{id}/revoke")
    @PreAuthorize("@authz.has('user:break-glass')")
    public ResponseEntity<?> revoke(@PathVariable Long id, HttpServletRequest request) {
        try {
            return ResponseEntity.ok(view(breakGlass.revoke(id, request)));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @PostMapping("/{id}/review")
    @PreAuthorize("@authz.has('user:approve')")
    public ResponseEntity<?> review(@PathVariable Long id,
                                    @RequestBody(required = false) Map<String, Object> body,
                                    HttpServletRequest request) {
        try {
            return ResponseEntity.ok(view(breakGlass.review(id,
                    body == null ? null : text(body.get("note")), request)));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    private static Map<String, Object> view(BreakGlassGrant grant) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", grant.getId());
        view.put("grantedTo", grant.getGrantedTo());
        view.put("reason", grant.getReason());
        view.put("grantedAt", grant.getGrantedAt());
        view.put("expiresAt", grant.getExpiresAt());
        view.put("revokedAt", grant.getRevokedAt());
        view.put("reviewedBy", grant.getReviewedBy());
        view.put("reviewedAt", grant.getReviewedAt());
        view.put("reviewNote", grant.getReviewNote());
        return view;
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static Integer asInt(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.valueOf(value.toString().trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}

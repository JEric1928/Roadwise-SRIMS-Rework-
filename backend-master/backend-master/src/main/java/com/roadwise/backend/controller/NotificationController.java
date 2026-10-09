package com.roadwise.backend.controller;

import com.roadwise.backend.model.Notification;
import com.roadwise.backend.model.User;
import com.roadwise.backend.repository.NotificationRepository;
import com.roadwise.backend.util.SecurityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    @Autowired
    private NotificationRepository notificationRepository;

    // ==========================================
    // 1. GET ALL NOTIFICATIONS FOR THE AUTHENTICATED USER
    // ==========================================
    @GetMapping("/me")
    public ResponseEntity<?> getMyNotifications() {
        User me = SecurityUtils.getCurrentUser();
        if (me == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not authenticated"));
        }
        List<Notification> notifications = notificationRepository.findByRecipientIdOrderByCreatedAtDesc(me.getId());
        return ResponseEntity.ok(notifications);
    }

    // ==========================================
    // 2. GET UNREAD BADGE COUNT (authenticated user)
    // ==========================================
    @GetMapping("/me/unread-count")
    public ResponseEntity<?> getMyUnreadCount() {
        User me = SecurityUtils.getCurrentUser();
        if (me == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not authenticated"));
        }
        long count = notificationRepository.countByRecipientIdAndIsReadFalse(me.getId());
        return ResponseEntity.ok(count);
    }

    // ==========================================
    // 3. MARK A SINGLE NOTIFICATION AS READ
    //    Ownership is enforced: users can only mark their own notifications.
    // ==========================================
    @PutMapping("/{id}/read")
    public ResponseEntity<?> markAsRead(@PathVariable Long id) {
        User me = SecurityUtils.getCurrentUser();
        if (me == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not authenticated"));
        }
        Optional<Notification> notifOpt = notificationRepository.findById(id);
        if (notifOpt.isPresent()) {
            Notification notif = notifOpt.get();
            if (notif.getRecipient() == null || !notif.getRecipient().getId().equals(me.getId())) {
                // Do not disclose existence of other users' notifications.
                return ResponseEntity.notFound().build();
            }
            notif.setRead(true);
            notificationRepository.save(notif);
            return ResponseEntity.ok("Notification marked as read.");
        }
        return ResponseEntity.notFound().build();
    }

    // ==========================================
    // 4. MARK ALL AS READ (For the Dropdown Button)
    // ==========================================
    @PutMapping("/me/read-all")
    public ResponseEntity<?> markAllAsRead() {
        User me = SecurityUtils.getCurrentUser();
        if (me == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not authenticated"));
        }
        // Fetch all notifications, filter for only unread ones, and mark them read
        List<Notification> unreadNotifs = notificationRepository.findByRecipientIdOrderByCreatedAtDesc(me.getId())
                .stream()
                .filter(n -> !n.isRead())
                .collect(Collectors.toList());

        for (Notification notif : unreadNotifs) {
            notif.setRead(true);
        }

        notificationRepository.saveAll(unreadNotifs);
        return ResponseEntity.ok("All notifications marked as read.");
    }
}

package com.roadwise.backend.controller;

import com.roadwise.backend.model.Notification;
import com.roadwise.backend.model.User;
import com.roadwise.backend.repository.NotificationRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/notifications")
@CrossOrigin(origins = "${frontend.url}")
public class NotificationController {

    private final NotificationRepository notificationRepository;

    public NotificationController(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    private User getAuthenticatedUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof User user) {
            return user;
        }
        throw new AccessDeniedException("User is not authenticated.");
    }

    @GetMapping("/me")
    public ResponseEntity<List<Notification>> getMyNotifications() {
        User user = getAuthenticatedUser();
        return ResponseEntity.ok(notificationRepository.findByRecipientIdOrderByCreatedAtDesc(user.getId()));
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<List<Notification>> getUserNotifications(@PathVariable Long userId) {
        User user = getAuthenticatedUser();
        if (!user.getId().equals(userId)) {
            throw new AccessDeniedException("Cannot access another user's notifications.");
        }
        return getMyNotifications();
    }

    @GetMapping("/me/unread-count")
    public ResponseEntity<Long> getMyUnreadCount() {
        User user = getAuthenticatedUser();
        return ResponseEntity.ok(notificationRepository.countByRecipientIdAndIsReadFalse(user.getId()));
    }

    @GetMapping("/user/{userId}/unread-count")
    public ResponseEntity<Long> getUnreadCount(@PathVariable Long userId) {
        User user = getAuthenticatedUser();
        if (!user.getId().equals(userId)) {
            throw new AccessDeniedException("Cannot access another user's notifications.");
        }
        return getMyUnreadCount();
    }

    @PutMapping("/{id}/read")
    public ResponseEntity<?> markAsRead(@PathVariable Long id) {
        User user = getAuthenticatedUser();
        Optional<Notification> notifOpt = notificationRepository.findByIdAndRecipientId(id, user.getId());
        if (notifOpt.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "Notification not found."));
        }

        Notification notif = notifOpt.get();
        notif.setRead(true);
        notificationRepository.save(notif);
        return ResponseEntity.ok(Map.of("message", "Notification marked as read."));
    }

    @PutMapping("/me/read-all")
    public ResponseEntity<?> markAllAsRead() {
        User user = getAuthenticatedUser();
        List<Notification> unreadNotifs = notificationRepository.findByRecipientIdOrderByCreatedAtDesc(user.getId())
                .stream()
                .filter(n -> !n.isRead())
                .collect(Collectors.toList());

        unreadNotifs.forEach(notif -> notif.setRead(true));
        notificationRepository.saveAll(unreadNotifs);
        return ResponseEntity.ok(Map.of("message", "All notifications marked as read."));
    }

    @PutMapping("/user/{userId}/read-all")
    public ResponseEntity<?> markAllAsRead(@PathVariable Long userId) {
        User user = getAuthenticatedUser();
        if (!user.getId().equals(userId)) {
            throw new AccessDeniedException("Cannot modify another user's notifications.");
        }
        return markAllAsRead();
    }
}

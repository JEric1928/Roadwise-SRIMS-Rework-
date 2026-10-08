package com.roadwise.backend.repository;

import com.roadwise.backend.model.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    // Traverses the recipient object to its primary key ID
    @Query("SELECT n FROM Notification n WHERE n.recipient.id = :recipientId ORDER BY n.createdAt DESC")
    List<Notification> findByRecipientIdOrderByCreatedAtDesc(@Param("recipientId") Long recipientId);

    // Counts unread notifications via recipient.id
    @Query("SELECT COUNT(n) FROM Notification n WHERE n.recipient.id = :recipientId AND n.isRead = false")
    long countByRecipientIdAndIsReadFalse(@Param("recipientId") Long recipientId);

    @Query("SELECT n FROM Notification n WHERE n.id = :id AND n.recipient.id = :recipientId")
    Optional<Notification> findByIdAndRecipientId(@Param("id") Long id, @Param("recipientId") Long recipientId);
}
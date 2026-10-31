package com.team.dating_backend.notification.repository;

import com.team.dating_backend.notification.entity.NotificationRequestOutbox;
import com.team.dating_backend.notification.enums.NotificationOutboxStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRequestOutboxRepository extends JpaRepository<NotificationRequestOutbox, Long> {

    @Query("""
        SELECT outbox FROM NotificationRequestOutbox outbox
        WHERE outbox.status = :status AND outbox.nextAttemptAt <= :now
        ORDER BY outbox.nextAttemptAt ASC, outbox.id ASC
        """)
    List<NotificationRequestOutbox> findDue(
        @Param("status") NotificationOutboxStatus status,
        @Param("now") LocalDateTime now,
        Pageable pageable);

    @Modifying
    @Query("""
        UPDATE NotificationRequestOutbox outbox
        SET outbox.status = :publishedStatus, outbox.publishedAt = :publishedAt
        WHERE outbox.id = :id AND outbox.status = :pendingStatus
        """)
    int markPublished(
        @Param("id") Long id,
        @Param("publishedAt") LocalDateTime publishedAt,
        @Param("pendingStatus") NotificationOutboxStatus pendingStatus,
        @Param("publishedStatus") NotificationOutboxStatus publishedStatus);

    @Modifying
    @Query("""
        UPDATE NotificationRequestOutbox outbox
        SET outbox.failureCount = :failureCount, outbox.lastFailureType = :failureType,
            outbox.nextAttemptAt = :nextAttemptAt, outbox.status = :status
        WHERE outbox.id = :id AND outbox.status = :pendingStatus AND outbox.failureCount = :previousCount
        """)
    int recordFailure(
        @Param("id") Long id,
        @Param("previousCount") int previousCount,
        @Param("failureCount") int failureCount,
        @Param("failureType") String failureType,
        @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
        @Param("pendingStatus") NotificationOutboxStatus pendingStatus,
        @Param("status") NotificationOutboxStatus status);
}

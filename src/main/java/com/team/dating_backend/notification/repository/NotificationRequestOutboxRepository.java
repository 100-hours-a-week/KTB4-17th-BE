package com.team.dating_backend.notification.repository;

import com.team.dating_backend.notification.entity.NotificationRequestOutbox;
import com.team.dating_backend.notification.enums.NotificationOutboxStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRequestOutboxRepository extends JpaRepository<NotificationRequestOutbox, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT outbox FROM NotificationRequestOutbox outbox WHERE outbox.id = :id")
    Optional<NotificationRequestOutbox> findByIdForUpdate(@Param("id") Long id);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
        UPDATE notification_request_outbox
        SET processing_token = :token,
            processing_until = TIMESTAMPADD(MICROSECOND, :durationMicros, UTC_TIMESTAMP(6))
        WHERE id = :id AND status = 'PENDING' AND next_attempt_at <= UTC_TIMESTAMP(6)
            AND (processing_token IS NULL OR processing_until <= UTC_TIMESTAMP(6))
        """, nativeQuery = true)
    int claim(@Param("id") Long id, @Param("token") String token, @Param("durationMicros") long durationMicros);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
        UPDATE notification_request_outbox
        SET status = 'PUBLISHED', published_at = UTC_TIMESTAMP(6),
            processing_token = NULL, processing_until = NULL
        WHERE id = :id AND status = 'PENDING' AND processing_token = :token
            AND processing_until > UTC_TIMESTAMP(6)
        """, nativeQuery = true)
    int markPublishedByToken(@Param("id") Long id, @Param("token") String token);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
        UPDATE notification_request_outbox
        SET failure_count = :failureCount, last_failure_type = :failureType,
            next_attempt_at = TIMESTAMPADD(MICROSECOND, :delayMicros, UTC_TIMESTAMP(6)), status = :status,
            processing_token = NULL, processing_until = NULL
        WHERE id = :id AND status = 'PENDING' AND failure_count = :previousCount
            AND processing_token = :token AND processing_until > UTC_TIMESTAMP(6)
        """, nativeQuery = true)
    int recordFailureByToken(
        @Param("id") Long id,
        @Param("token") String token,
        @Param("previousCount") int previousCount,
        @Param("failureCount") int failureCount,
        @Param("failureType") String failureType,
        @Param("delayMicros") long delayMicros,
        @Param("status") String status);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
        UPDATE notification_request_outbox
        SET processing_token = NULL, processing_until = NULL
        WHERE id = :id AND status = 'PENDING' AND processing_token = :token
            AND processing_until > UTC_TIMESTAMP(6)
        """, nativeQuery = true)
    int releaseByToken(@Param("id") Long id, @Param("token") String token);

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
            AND outbox.processingToken IS NULL AND outbox.processingUntil IS NULL
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
            AND outbox.processingToken IS NULL AND outbox.processingUntil IS NULL
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

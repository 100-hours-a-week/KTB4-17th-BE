package com.team.dating_backend.notification.entity;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.notification.enums.NotificationOutboxStatus;
import com.team.dating_backend.notification.enums.NotificationType;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
    name = "notification_request_outbox",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_notification_outbox_source_recipient_type",
        columnNames = {"source_event_id", "recipient_user_id", "type"}
    ),
    indexes = @Index(
        name = "idx_notification_outbox_due",
        columnList = "status,next_attempt_at,id"
    )
)
public class NotificationRequestOutbox {

    private static final int MAX_SOURCE_EVENT_ID_LENGTH = 255;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_event_id", nullable = false, updatable = false, length = MAX_SOURCE_EVENT_ID_LENGTH)
    private String sourceEventId;

    @Column(name = "recipient_user_id", nullable = false, updatable = false)
    private Long recipientUserId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "type", nullable = false, updatable = false, length = 30)
    private NotificationType type;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "payload", nullable = false, updatable = false, columnDefinition = "longtext")
    private String payload;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 16)
    private NotificationOutboxStatus status;

    @Column(name = "failure_count", nullable = false)
    private int failureCount;

    @Column(name = "last_failure_type", length = 100)
    private String lastFailureType;

    @Column(name = "next_attempt_at", nullable = false)
    private LocalDateTime nextAttemptAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    public NotificationRequestOutbox(String sourceEventId, Long recipientUserId, NotificationType type,
        String payload, LocalDateTime createdAt) {
        if (sourceEventId == null || sourceEventId.isBlank()
            || sourceEventId.codePointCount(0, sourceEventId.length()) > MAX_SOURCE_EVENT_ID_LENGTH
            || recipientUserId == null || recipientUserId <= 0
            || type == null || payload == null || payload.isBlank() || createdAt == null) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
        this.sourceEventId = sourceEventId;
        this.recipientUserId = recipientUserId;
        this.type = type;
        this.payload = payload;
        this.status = NotificationOutboxStatus.PENDING;
        this.failureCount = 0;
        this.createdAt = createdAt.truncatedTo(ChronoUnit.MICROS);
        this.nextAttemptAt = this.createdAt;
    }
}

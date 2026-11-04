package com.team.dating_backend.notification.entity;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.notification.enums.NotificationType;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import com.team.dating_backend.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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
    name = "notifications",
    indexes = {
        @Index(
            name = "idx_notifications_user_deleted_id",
            columnList = "user_id,deleted_at,id"
        ),
        @Index(
            name = "idx_notifications_user_deleted_created_id",
            columnList = "user_id,deleted_at,created_at,id"
        )
    },
    uniqueConstraints = @UniqueConstraint(
        name = "uk_notifications_source_recipient_type",
        columnNames = {"source_event_id", "user_id", "type"}
    )
)
public class Notification {

    private static final int MAX_SOURCE_EVENT_ID_LENGTH = 255;
    private static final int MAX_TITLE_LENGTH = 100;
    private static final int MAX_BODY_LENGTH = 500;
    private static final int MAX_TARGET_TYPE_LENGTH = 50;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_event_id", updatable = false, length = MAX_SOURCE_EVENT_ID_LENGTH)
    private String sourceEventId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
        name = "user_id",
        nullable = false,
        updatable = false,
        foreignKey = @ForeignKey(name = "fk_notifications_user_id")
    )
    private User user;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "type", nullable = false, updatable = false, length = 30)
    private NotificationType type;

    @Column(name = "title", nullable = false, updatable = false, length = MAX_TITLE_LENGTH)
    private String title;

    @Column(name = "body", nullable = false, updatable = false, length = MAX_BODY_LENGTH)
    private String body;

    @Column(name = "target_type", updatable = false, length = MAX_TARGET_TYPE_LENGTH)
    private String targetType;

    @Column(name = "target_id", updatable = false)
    private Long targetId;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public Notification(
        User user,
        NotificationType type,
        String title,
        String body,
        String targetType,
        Long targetId,
        LocalDateTime createdAt) {
        validateTarget(targetType, targetId);

        this.user = requirePresent(user);
        this.type = requirePresent(type);
        this.title = requireTextWithinLength(title, MAX_TITLE_LENGTH);
        this.body = requireTextWithinLength(body, MAX_BODY_LENGTH);
        this.targetType = targetType;
        this.targetId = targetId;
        this.createdAt = requirePresent(createdAt).truncatedTo(ChronoUnit.MICROS);
    }

    public static Notification fromSourceEvent(
        String sourceEventId,
        User user,
        NotificationType type,
        String title,
        String body,
        String targetType,
        Long targetId,
        LocalDateTime createdAt) {
        requireTextWithinLength(sourceEventId, MAX_SOURCE_EVENT_ID_LENGTH);
        if (sourceEventId.isBlank()) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }

        Notification notification = new Notification(user, type, title, body, targetType, targetId, createdAt);
        notification.sourceEventId = sourceEventId;
        return notification;
    }

    public Long getUserId() {
        return user.getId();
    }

    public void markAsRead(LocalDateTime now) {
        requirePresent(now);
        if (readAt == null) {
            readAt = now.truncatedTo(ChronoUnit.MICROS);
        }
    }

    public void markAsDeleted(LocalDateTime now) {
        requirePresent(now);
        if (deletedAt == null) {
            deletedAt = now.truncatedTo(ChronoUnit.MICROS);
        }
    }

    private static void validateTarget(String targetType, Long targetId) {
        if ((targetType == null) != (targetId == null)) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
        if (targetType == null) {
            return;
        }
        requireTextWithinLength(targetType, MAX_TARGET_TYPE_LENGTH);
        if (targetType.isBlank() || targetId <= 0) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    private static String requireTextWithinLength(String value, int maxLength) {
        requirePresent(value);
        if (value.codePointCount(0, value.length()) > maxLength) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
        return value;
    }

    private static <T> T requirePresent(T value) {
        if (value == null) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
        return value;
    }
}

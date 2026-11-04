package com.team.dating_backend.notification.dto.event;

import com.team.dating_backend.notification.enums.NotificationType;
import java.time.Instant;

public record NotificationCreateRequested(
    int schemaVersion,
    String sourceEventId,
    Long recipientUserId,
    NotificationType type,
    String title,
    String body,
    String targetType,
    Long targetId,
    Instant requestedAt) {

    public static final int CURRENT_SCHEMA_VERSION = 1;
}

package com.team.dating_backend.notification.dto.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.team.dating_backend.notification.enums.NotificationType;
import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record NotificationCreateRequested(
    String sourceEventId,
    Long recipientUserId,
    NotificationType type,
    String title,
    String body,
    String targetType,
    Long targetId,
    Instant requestedAt) {}

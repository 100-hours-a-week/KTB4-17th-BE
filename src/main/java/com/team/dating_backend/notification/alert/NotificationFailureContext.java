package com.team.dating_backend.notification.alert;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import com.team.dating_backend.notification.enums.NotificationFailureCode;
import com.team.dating_backend.notification.enums.NotificationType;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import java.time.Instant;

public record NotificationFailureContext(
    NotificationFailureCode errorCode,
    String sourceEventId,
    Long recipientUserId,
    NotificationType type,
    Instant requestedAt,
    Instant occurredAt,
    String exceptionType,
    String causeType) {

    public NotificationFailureContext {
        if (errorCode == null || occurredAt == null) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    public static NotificationFailureContext from(
        NotificationFailureCode errorCode,
        NotificationCreateRequested request,
        Throwable exception,
        Instant occurredAt) {
        return new NotificationFailureContext(
            errorCode,
            request == null ? null : request.sourceEventId(),
            request == null ? null : request.recipientUserId(),
            request == null ? null : request.type(),
            request == null ? null : request.requestedAt(),
            occurredAt,
            exception == null ? null : exception.getClass().getSimpleName(),
            exception == null || exception.getCause() == null ? null : exception.getCause().getClass().getSimpleName());
    }
}

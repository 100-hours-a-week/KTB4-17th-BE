package com.team.dating_backend.notification.dto.result;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.notification.dto.response.NotificationEventResponse;
import com.team.dating_backend.notification.entity.Notification;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import java.time.LocalDateTime;

public record NotificationCreationResult(
    String sourceEventId,
    Long recipientUserId,
    NotificationEventResponse notification,
    LocalDateTime deletedAt) {

    public NotificationCreationResult {
        if (sourceEventId == null || sourceEventId.isBlank()
            || recipientUserId == null || recipientUserId <= 0
            || notification == null || notification.notificationId() == null) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    public static NotificationCreationResult from(Notification notification) {
        NotificationEventResponse response = NotificationEventResponse.from(notification);
        return new NotificationCreationResult(
            notification.getSourceEventId(),
            notification.getUserId(),
            response,
            notification.getDeletedAt());
    }
}

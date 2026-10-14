package com.team.dating_backend.notification.dto.response;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.notification.entity.Notification;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import java.time.LocalDateTime;

public record NotificationEventResponse(
    Long notificationId,
    String type,
    String title,
    String body,
    String targetType,
    Long targetId,
    LocalDateTime readAt,
    LocalDateTime createdAt) {

    public static NotificationEventResponse from(Notification notification) {
        if (notification == null || notification.getId() == null) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }

        return new NotificationEventResponse(
            notification.getId(),
            notification.getType().name(),
            notification.getTitle(),
            notification.getBody(),
            notification.getTargetType(),
            notification.getTargetId(),
            notification.getReadAt(),
            notification.getCreatedAt());
    }
}

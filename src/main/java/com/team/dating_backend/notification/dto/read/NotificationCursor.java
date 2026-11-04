package com.team.dating_backend.notification.dto.read;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import java.time.LocalDateTime;

public record NotificationCursor(
    LocalDateTime createdAt,
    Long notificationId) {

    public NotificationCursor {
        if (createdAt == null || notificationId == null || notificationId <= 0
            || createdAt.getYear() < 1000 || createdAt.getYear() > 9999
            || createdAt.getNano() % 1_000 != 0) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
    }
}

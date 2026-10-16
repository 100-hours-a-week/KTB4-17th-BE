package com.team.dating_backend.notification.dto.event;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.notification.dto.response.NotificationEventResponse;
import com.team.dating_backend.notification.entity.Notification;
import com.team.dating_backend.notification.exception.NotificationBusinessException;

public record NotificationCreatedEvent(
    Long recipientUserId,
    NotificationEventResponse notification) {

    public static NotificationCreatedEvent from(Notification notification) {
        NotificationEventResponse response = NotificationEventResponse.from(notification);
        Long recipientUserId = notification.getUserId();
        if (recipientUserId == null || recipientUserId <= 0) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }

        return new NotificationCreatedEvent(recipientUserId, response);
    }
}

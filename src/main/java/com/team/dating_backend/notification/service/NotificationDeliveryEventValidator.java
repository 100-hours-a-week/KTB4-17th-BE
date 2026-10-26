package com.team.dating_backend.notification.service;

import com.team.dating_backend.notification.dto.event.NotificationDeliveryEvent;
import com.team.dating_backend.notification.enums.NotificationType;
import com.team.dating_backend.notification.exception.NotificationInvalidDeliveryEventException;
import org.springframework.stereotype.Component;

@Component
public class NotificationDeliveryEventValidator {

    private static final int MAX_SOURCE_EVENT_ID_LENGTH = 255;
    private static final int MAX_TITLE_LENGTH = 100;
    private static final int MAX_BODY_LENGTH = 500;
    private static final int MAX_TARGET_TYPE_LENGTH = 50;

    public void validate(NotificationDeliveryEvent event) {
        if (event == null
            || event.schemaVersion() != NotificationDeliveryEvent.CURRENT_SCHEMA_VERSION
            || event.sourceEventId() == null
            || event.sourceEventId().isBlank()
            || event.recipientUserId() == null
            || event.recipientUserId() <= 0
            || event.notification() == null) {
            throw new NotificationInvalidDeliveryEventException();
        }

        validateTextWithinLength(event.sourceEventId(), MAX_SOURCE_EVENT_ID_LENGTH);

        var notification = event.notification();
        if (notification.notificationId() == null
            || notification.notificationId() <= 0
            || notification.createdAt() == null) {
            throw new NotificationInvalidDeliveryEventException();
        }

        validateType(notification.type());
        validateTextWithinLength(notification.title(), MAX_TITLE_LENGTH);
        validateTextWithinLength(notification.body(), MAX_BODY_LENGTH);
        validateTarget(notification.targetType(), notification.targetId());
    }

    private void validateType(String type) {
        if (type == null) {
            throw new NotificationInvalidDeliveryEventException();
        }
        try {
            NotificationType.valueOf(type);
        } catch (IllegalArgumentException exception) {
            throw new NotificationInvalidDeliveryEventException(exception);
        }
    }

    private void validateTarget(String targetType, Long targetId) {
        if ((targetType == null) != (targetId == null)) {
            throw new NotificationInvalidDeliveryEventException();
        }
        if (targetType == null) {
            return;
        }
        validateTextWithinLength(targetType, MAX_TARGET_TYPE_LENGTH);
        if (targetType.isBlank() || targetId <= 0) {
            throw new NotificationInvalidDeliveryEventException();
        }
    }

    private void validateTextWithinLength(String value, int maxLength) {
        if (value == null || value.codePointCount(0, value.length()) > maxLength) {
            throw new NotificationInvalidDeliveryEventException();
        }
    }
}

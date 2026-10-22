package com.team.dating_backend.notification.service;

import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import com.team.dating_backend.notification.exception.NotificationInvalidCreateRequestException;
import org.springframework.stereotype.Component;

@Component
public class NotificationCreateRequestedValidator {

    private static final int MAX_SOURCE_EVENT_ID_LENGTH = 255;
    private static final int MAX_TITLE_LENGTH = 100;
    private static final int MAX_BODY_LENGTH = 500;
    private static final int MAX_TARGET_TYPE_LENGTH = 50;

    public void validate(NotificationCreateRequested request) {
        if (request == null
            || request.schemaVersion() != NotificationCreateRequested.CURRENT_SCHEMA_VERSION
            || request.sourceEventId() == null
            || request.sourceEventId().isBlank()
            || request.recipientUserId() == null
            || request.recipientUserId() <= 0
            || request.type() == null
            || request.requestedAt() == null) {
            throw new NotificationInvalidCreateRequestException();
        }

        validateTextWithinLength(request.sourceEventId(), MAX_SOURCE_EVENT_ID_LENGTH);
        validateTextWithinLength(request.title(), MAX_TITLE_LENGTH);
        validateTextWithinLength(request.body(), MAX_BODY_LENGTH);
        validateTarget(request.targetType(), request.targetId());
    }

    private void validateTarget(String targetType, Long targetId) {
        if ((targetType == null) != (targetId == null)) {
            throw new NotificationInvalidCreateRequestException();
        }
        if (targetType == null) {
            return;
        }
        validateTextWithinLength(targetType, MAX_TARGET_TYPE_LENGTH);
        if (targetType.isBlank() || targetId <= 0) {
            throw new NotificationInvalidCreateRequestException();
        }
    }

    private void validateTextWithinLength(String value, int maxLength) {
        if (value == null || value.codePointCount(0, value.length()) > maxLength) {
            throw new NotificationInvalidCreateRequestException();
        }
    }
}

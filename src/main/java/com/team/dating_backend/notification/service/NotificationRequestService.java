package com.team.dating_backend.notification.service;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@RequiredArgsConstructor
public class NotificationRequestService {

    private static final int MAX_TITLE_LENGTH = 100;
    private static final int MAX_BODY_LENGTH = 500;
    private static final int MAX_TARGET_TYPE_LENGTH = 50;

    private final ApplicationEventPublisher eventPublisher;

    @Transactional(propagation = Propagation.MANDATORY)
    public void requestNotification(NotificationCreateRequested request) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
            || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
        validateRequest(request);
        eventPublisher.publishEvent(request);
    }

    private static void validateRequest(NotificationCreateRequested request) {
        if (request == null
            || request.schemaVersion() != NotificationCreateRequested.CURRENT_SCHEMA_VERSION
            || request.sourceEventId() == null
            || request.sourceEventId().isBlank()
            || request.recipientUserId() == null
            || request.recipientUserId() <= 0
            || request.type() == null
            || request.requestedAt() == null) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }

        validateTextWithinLength(request.title(), MAX_TITLE_LENGTH);
        validateTextWithinLength(request.body(), MAX_BODY_LENGTH);
        validateTarget(request.targetType(), request.targetId());
    }

    private static void validateTarget(String targetType, Long targetId) {
        if ((targetType == null) != (targetId == null)) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
        if (targetType == null) {
            return;
        }
        validateTextWithinLength(targetType, MAX_TARGET_TYPE_LENGTH);
        if (targetType.isBlank() || targetId <= 0) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    private static void validateTextWithinLength(String value, int maxLength) {
        if (value == null || value.codePointCount(0, value.length()) > maxLength) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
    }
}

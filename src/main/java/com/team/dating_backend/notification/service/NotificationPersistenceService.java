package com.team.dating_backend.notification.service;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import com.team.dating_backend.notification.dto.result.NotificationCreationResult;
import com.team.dating_backend.notification.entity.Notification;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import com.team.dating_backend.notification.repository.NotificationRepository;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NotificationPersistenceService {

    private final UserRepository userRepository;
    private final NotificationRepository notificationRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public NotificationCreationResult create(NotificationCreateRequested request) {
        if (request == null
            || request.schemaVersion() != NotificationCreateRequested.CURRENT_SCHEMA_VERSION
            || request.recipientUserId() == null
            || request.recipientUserId() <= 0
            || request.requestedAt() == null) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }

        User recipient = userRepository.getReferenceById(request.recipientUserId());
        Notification notification = Notification.fromSourceEvent(
            request.sourceEventId(),
            recipient,
            request.type(),
            request.title(),
            request.body(),
            request.targetType(),
            request.targetId(),
            LocalDateTime.ofInstant(request.requestedAt(), ZoneOffset.UTC));
        Notification savedNotification = notificationRepository.saveAndFlush(notification);

        return NotificationCreationResult.from(savedNotification);
    }
}

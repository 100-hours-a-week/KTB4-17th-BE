package com.team.dating_backend.notification.service;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import com.team.dating_backend.notification.dto.result.NotificationCreationResult;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import com.team.dating_backend.notification.repository.NotificationRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NotificationLookupService {

    private final NotificationRepository notificationRepository;

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Optional<NotificationCreationResult> findExisting(NotificationCreateRequested request) {
        if (request == null
            || request.sourceEventId() == null
            || request.sourceEventId().isBlank()
            || request.recipientUserId() == null
            || request.recipientUserId() <= 0
            || request.type() == null) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }

        return notificationRepository.findBySourceEventIdAndUserIdAndType(
            request.sourceEventId(), request.recipientUserId(), request.type())
            .map(NotificationCreationResult::from);
    }
}

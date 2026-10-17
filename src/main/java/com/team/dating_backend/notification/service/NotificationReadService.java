package com.team.dating_backend.notification.service;

import com.team.dating_backend.notification.entity.Notification;
import com.team.dating_backend.notification.enums.NotificationErrorCode;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import com.team.dating_backend.notification.repository.NotificationRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NotificationReadService {

    private final NotificationAccessService notificationAccessService;
    private final NotificationRepository notificationRepository;

    @Transactional
    public void markAsRead(Long requesterUserId, Long notificationId) {
        notificationAccessService.assertActiveUser(requesterUserId);

        Notification notification = notificationRepository.findByIdForUpdate(notificationId)
            .orElseThrow(() -> new NotificationBusinessException(
                NotificationErrorCode.NOTIFICATION_NOT_FOUND));

        if (notification.getDeletedAt() != null) {
            throw new NotificationBusinessException(NotificationErrorCode.NOTIFICATION_NOT_FOUND);
        }

        if (!notification.getUserId().equals(requesterUserId)) {
            throw new NotificationBusinessException(NotificationErrorCode.NOTIFICATION_ACCESS_DENIED);
        }

        if (notification.getReadAt() != null) {
            return;
        }

        notification.markAsRead(LocalDateTime.now());
    }
}

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
public class NotificationDeleteService {

    private final NotificationAccessService notificationAccessService;
    private final NotificationRepository notificationRepository;

    @Transactional
    public void deleteNotification(Long requesterUserId, Long notificationId) {
        notificationAccessService.assertActiveUser(requesterUserId);

        Notification notification = notificationRepository.findByIdForUpdate(notificationId)
            .orElse(null);
        if (notification == null) {
            return;
        }

        if (!notification.getUserId().equals(requesterUserId)) {
            throw new NotificationBusinessException(
                NotificationErrorCode.NOTIFICATION_ACCESS_DENIED);
        }

        if (notification.getDeletedAt() != null) {
            return;
        }

        notification.markAsDeleted(LocalDateTime.now());
    }
}

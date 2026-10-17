package com.team.dating_backend.notification.service;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.notification.dto.event.NotificationCreatedEvent;
import com.team.dating_backend.notification.entity.Notification;
import com.team.dating_backend.notification.enums.NotificationType;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import com.team.dating_backend.notification.repository.NotificationRepository;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.repository.UserRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NotificationCreateService {

    private final UserRepository userRepository;
    private final NotificationRepository notificationRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(propagation = Propagation.MANDATORY)
    public void createNotification(
        Long recipientUserId,
        NotificationType type,
        String title,
        String body,
        String targetType,
        Long targetId) {
        if (recipientUserId == null || recipientUserId <= 0) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }

        User recipient = userRepository.getReferenceById(recipientUserId);
        Notification notification = new Notification(
            recipient, type, title, body, targetType, targetId, LocalDateTime.now());
        Notification savedNotification = notificationRepository.save(notification);

        eventPublisher.publishEvent(NotificationCreatedEvent.from(savedNotification));
    }
}

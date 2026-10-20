package com.team.dating_backend.notification.service;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import com.team.dating_backend.notification.repository.NotificationRepository;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChatNotificationReadService {

    private final NotificationRepository notificationRepository;

    @Transactional(propagation = Propagation.MANDATORY)
    public void markAsRead(Long recipientUserId, Long chatRoomId) {
        if (recipientUserId == null || recipientUserId <= 0
            || chatRoomId == null || chatRoomId <= 0) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }

        LocalDateTime readAt = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        notificationRepository.markVisibleUnreadChatNotificationsAsRead(
            recipientUserId, chatRoomId, readAt);
    }
}

package com.team.dating_backend.notification.service;

import com.team.dating_backend.notification.dto.response.NotificationUnreadCountResponse;
import com.team.dating_backend.notification.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NotificationUnreadCountService {

    private final NotificationAccessService notificationAccessService;
    private final NotificationRepository notificationRepository;

    @Transactional(readOnly = true)
    public NotificationUnreadCountResponse getUnreadCount(Long userId) {
        notificationAccessService.assertActiveUser(userId);

        long count = notificationRepository.countVisibleUnreadByUserId(userId);
        return new NotificationUnreadCountResponse(count);
    }
}

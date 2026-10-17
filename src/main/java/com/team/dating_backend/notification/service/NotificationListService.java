package com.team.dating_backend.notification.service;

import com.team.dating_backend.notification.dto.response.NotificationListResponse;
import com.team.dating_backend.notification.entity.Notification;
import com.team.dating_backend.notification.enums.NotificationCategory;
import com.team.dating_backend.notification.enums.NotificationType;
import com.team.dating_backend.notification.repository.NotificationRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NotificationListService {

    private final NotificationAccessService notificationAccessService;
    private final NotificationRepository notificationRepository;

    @Transactional(readOnly = true)
    public NotificationListResponse getNotifications(
        Long userId, NotificationCategory category, Long cursor, int size) {
        notificationAccessService.assertActiveUser(userId);

        List<Notification> notifications = findNotifications(
            userId, category, cursor, PageRequest.of(0, size + 1));
        boolean hasNext = notifications.size() > size;
        List<NotificationListResponse.Item> items = notifications.stream()
            .limit(size)
            .map(NotificationListResponse.Item::from)
            .toList();
        Long nextCursor = hasNext ? items.getLast().notificationId() : null;

        return new NotificationListResponse(
            items, new NotificationListResponse.PageInfo(nextCursor, hasNext));
    }

    private List<Notification> findNotifications(
        Long userId, NotificationCategory category, Long cursor, Pageable pageable) {
        if (category == NotificationCategory.ALL) {
            return cursor == null
                ? notificationRepository.findVisibleByUserId(userId, pageable)
                : notificationRepository.findVisibleByUserIdAndIdLessThan(userId, cursor, pageable);
        }

        List<NotificationType> types = category.getTypes();
        return cursor == null
            ? notificationRepository.findVisibleByUserIdAndTypes(userId, types, pageable)
            : notificationRepository.findVisibleByUserIdAndTypesAndIdLessThan(
                userId, types, cursor, pageable);
    }
}

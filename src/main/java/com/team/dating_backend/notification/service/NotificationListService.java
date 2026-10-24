package com.team.dating_backend.notification.service;

import com.team.dating_backend.notification.cursor.NotificationCursorCodec;
import com.team.dating_backend.notification.dto.read.NotificationCursor;
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
    private final NotificationCursorCodec notificationCursorCodec;

    @Transactional(readOnly = true)
    public NotificationListResponse getNotifications(
        Long userId, NotificationCategory category, NotificationCursor cursor, int size) {
        notificationAccessService.assertActiveUser(userId);

        List<Notification> notifications = findNotifications(
            userId, category, cursor, PageRequest.of(0, size + 1));
        boolean hasNext = notifications.size() > size;
        List<Notification> page = notifications.subList(0, Math.min(size, notifications.size()));
        List<NotificationListResponse.Item> items = page.stream()
            .map(NotificationListResponse.Item::from)
            .toList();
        String nextCursor = hasNext
            ? notificationCursorCodec.encode(new NotificationCursor(
                page.getLast().getCreatedAt(), page.getLast().getId()))
            : null;

        return new NotificationListResponse(
            items, new NotificationListResponse.PageInfo(nextCursor, hasNext));
    }

    private List<Notification> findNotifications(
        Long userId, NotificationCategory category, NotificationCursor cursor, Pageable pageable) {
        if (category == NotificationCategory.ALL) {
            return cursor == null
                ? notificationRepository.findVisibleByUserId(userId, pageable)
                : notificationRepository.findVisibleByUserIdBeforeCursor(
                    userId, cursor.createdAt(), cursor.notificationId(), pageable);
        }

        List<NotificationType> types = category.getTypes();
        return cursor == null
            ? notificationRepository.findVisibleByUserIdAndTypes(userId, types, pageable)
            : notificationRepository.findVisibleByUserIdAndTypesBeforeCursor(
                userId, types, cursor.createdAt(), cursor.notificationId(), pageable);
    }
}

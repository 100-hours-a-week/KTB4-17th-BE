package com.team.dating_backend.notification.dto.response;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.notification.entity.Notification;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import java.time.LocalDateTime;
import java.util.List;

public record NotificationListResponse(
    List<Item> items,
    PageInfo pageInfo) {

    public record Item(
        Long notificationId,
        String type,
        String title,
        String body,
        String targetType,
        Long targetId,
        LocalDateTime readAt,
        LocalDateTime createdAt) {

        public static Item from(Notification notification) {
            if (notification == null || notification.getId() == null) {
                throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
            }

            return new Item(
                notification.getId(),
                notification.getType().name(),
                notification.getTitle(),
                notification.getBody(),
                notification.getTargetType(),
                notification.getTargetId(),
                notification.getReadAt(),
                notification.getCreatedAt());
        }
    }

    public record PageInfo(String nextCursor, boolean hasNext) {}
}

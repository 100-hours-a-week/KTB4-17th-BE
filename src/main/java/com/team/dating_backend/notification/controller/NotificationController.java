package com.team.dating_backend.notification.controller;

import com.team.dating_backend.common.dto.response.SuccessResponse;
import com.team.dating_backend.notification.cursor.NotificationCursorCodec;
import com.team.dating_backend.notification.dto.read.NotificationCursor;
import com.team.dating_backend.notification.dto.response.NotificationListResponse;
import com.team.dating_backend.notification.dto.response.NotificationUnreadCountResponse;
import com.team.dating_backend.notification.enums.NotificationCategory;
import com.team.dating_backend.notification.enums.NotificationErrorCode;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import com.team.dating_backend.notification.service.NotificationDeleteService;
import com.team.dating_backend.notification.service.NotificationListService;
import com.team.dating_backend.notification.service.NotificationReadService;
import com.team.dating_backend.notification.service.NotificationUnreadCountService;
import com.team.dating_backend.security.ServiceAuthenticationPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 20;

    private final NotificationDeleteService notificationDeleteService;
    private final NotificationListService notificationListService;
    private final NotificationUnreadCountService notificationUnreadCountService;
    private final NotificationReadService notificationReadService;
    private final NotificationCursorCodec notificationCursorCodec;

    @GetMapping
    public ResponseEntity<SuccessResponse<NotificationListResponse>> getNotifications(
        @AuthenticationPrincipal ServiceAuthenticationPrincipal principal,
        @RequestParam(value = "category", required = false) String category,
        @RequestParam(value = "cursor", required = false) String cursor,
        @RequestParam(value = "size", required = false) String size) {
        NotificationCategory parsedCategory = parseCategory(category);
        NotificationCursor parsedCursor = parseCursor(cursor);
        int parsedSize = parseSize(size);

        NotificationListResponse response = notificationListService.getNotifications(
            principal.userId(), parsedCategory, parsedCursor, parsedSize);
        return ResponseEntity.ok(SuccessResponse.of("notification_list_success", response));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<SuccessResponse<NotificationUnreadCountResponse>> getUnreadCount(
        @AuthenticationPrincipal ServiceAuthenticationPrincipal principal) {
        NotificationUnreadCountResponse response = notificationUnreadCountService.getUnreadCount(
            principal.userId());
        return ResponseEntity.ok(SuccessResponse.of("notification_unread_count_success", response));
    }

    @PutMapping("/{notificationId}/read-receipt")
    public ResponseEntity<Void> markAsRead(
        @AuthenticationPrincipal ServiceAuthenticationPrincipal principal,
        @PathVariable("notificationId") String notificationId) {
        Long parsedNotificationId = parseNotificationId(notificationId);
        notificationReadService.markAsRead(principal.userId(), parsedNotificationId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{notificationId}")
    public ResponseEntity<Void> deleteNotification(
        @AuthenticationPrincipal ServiceAuthenticationPrincipal principal,
        @PathVariable("notificationId") String notificationId) {
        Long parsedNotificationId = parseNotificationId(notificationId);
        notificationDeleteService.deleteNotification(principal.userId(), parsedNotificationId);
        return ResponseEntity.noContent().build();
    }

    private NotificationCategory parseCategory(String category) {
        if (category == null) {
            return NotificationCategory.ALL;
        }

        try {
            return NotificationCategory.valueOf(category);
        } catch (IllegalArgumentException exception) {
            throw new NotificationBusinessException(
                NotificationErrorCode.INVALID_NOTIFICATION_CATEGORY);
        }
    }

    private NotificationCursor parseCursor(String cursor) {
        if (cursor == null) {
            return null;
        }

        return notificationCursorCodec.decode(cursor);
    }

    private int parseSize(String size) {
        if (size == null) {
            return DEFAULT_PAGE_SIZE;
        }

        try {
            int parsedSize = Integer.parseInt(size);
            if (parsedSize < 1 || parsedSize > MAX_PAGE_SIZE) {
                throw new NotificationBusinessException(
                    NotificationErrorCode.INVALID_NOTIFICATION_PAGE_SIZE);
            }
            return parsedSize;
        } catch (NumberFormatException exception) {
            throw new NotificationBusinessException(
                NotificationErrorCode.INVALID_NOTIFICATION_PAGE_SIZE);
        }
    }

    private Long parseNotificationId(String notificationId) {
        try {
            long parsedNotificationId = Long.parseLong(notificationId);
            if (parsedNotificationId <= 0) {
                throw new NotificationBusinessException(
                    NotificationErrorCode.INVALID_NOTIFICATION_ID);
            }
            return parsedNotificationId;
        } catch (NumberFormatException exception) {
            throw new NotificationBusinessException(NotificationErrorCode.INVALID_NOTIFICATION_ID);
        }
    }
}

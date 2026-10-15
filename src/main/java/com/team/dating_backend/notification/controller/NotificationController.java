package com.team.dating_backend.notification.controller;

import com.team.dating_backend.notification.enums.NotificationErrorCode;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import com.team.dating_backend.notification.service.NotificationDeleteService;
import com.team.dating_backend.security.ServiceAuthenticationPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationDeleteService notificationDeleteService;

    @DeleteMapping("/{notificationId}")
    public ResponseEntity<Void> deleteNotification(
        @AuthenticationPrincipal ServiceAuthenticationPrincipal principal,
        @PathVariable("notificationId") String notificationId) {
        Long parsedNotificationId = parseNotificationId(notificationId);
        notificationDeleteService.deleteNotification(principal.userId(), parsedNotificationId);
        return ResponseEntity.noContent().build();
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

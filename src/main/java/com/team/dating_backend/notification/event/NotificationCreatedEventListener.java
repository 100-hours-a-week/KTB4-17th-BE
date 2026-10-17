package com.team.dating_backend.notification.event;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.notification.dto.event.NotificationCreatedEvent;
import com.team.dating_backend.notification.sse.NotificationSseSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationCreatedEventListener {

    private final NotificationSseSender sender;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onNotificationCreated(NotificationCreatedEvent event) {
        try {
            sender.sendNotification(event.recipientUserId(), event.notification());
        } catch (RuntimeException exception) {
            log.error(
                "Notification delivery failed after commit. errorCode={}, userId={}, notificationId={}, exceptionType={}, causeType={}",
                CommonErrorCode.INTERNAL_SERVER_ERROR, event.recipientUserId(),
                event.notification().notificationId(), exception.getClass().getSimpleName(),
                exception.getCause() == null ? null : exception.getCause().getClass().getSimpleName());
        }
    }
}

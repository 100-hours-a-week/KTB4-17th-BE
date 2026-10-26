package com.team.dating_backend.notification.event;

import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import com.team.dating_backend.notification.kafka.NotificationPublishDispatcher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class NotificationCreateRequestedEventListener {

    private final NotificationPublishDispatcher publishDispatcher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onNotificationCreateRequested(NotificationCreateRequested request) {
        publishDispatcher.dispatch(request);
    }
}

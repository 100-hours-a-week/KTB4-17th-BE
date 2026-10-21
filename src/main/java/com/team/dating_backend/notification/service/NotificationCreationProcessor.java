package com.team.dating_backend.notification.service;

import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import com.team.dating_backend.notification.dto.event.NotificationDeliveryEvent;
import com.team.dating_backend.notification.dto.result.NotificationCreationResult;
import com.team.dating_backend.notification.kafka.NotificationDeliveryProducer;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NotificationCreationProcessor {

    private final NotificationCreateRequestedValidator validator;
    private final NotificationCreationService creationService;
    private final NotificationDeliveryProducer deliveryProducer;

    @Transactional(propagation = Propagation.NEVER)
    public void process(NotificationCreateRequested request)
        throws ExecutionException, InterruptedException, TimeoutException {
        validator.validate(request);
        NotificationCreationResult result = creationService.createOrLoad(request);
        if (result.deletedAt() != null) {
            return;
        }

        NotificationDeliveryEvent event = new NotificationDeliveryEvent(
            NotificationDeliveryEvent.CURRENT_SCHEMA_VERSION,
            result.sourceEventId(),
            result.recipientUserId(),
            result.notification());
        deliveryProducer.publishAndAwait(event);
    }
}

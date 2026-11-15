package com.team.dating_backend.notification.service;

import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import com.team.dating_backend.notification.dto.event.NotificationDeliveryEvent;
import com.team.dating_backend.notification.dto.result.NotificationCreationResult;
import com.team.dating_backend.notification.kafka.NotificationDeliveryProducer;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
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
        process(request, stage -> {});
    }

    @Transactional(propagation = Propagation.NEVER)
    public void process(NotificationCreateRequested request, Consumer<Stage> stageListener)
        throws ExecutionException, InterruptedException, TimeoutException {
        stageListener.accept(Stage.CREATION);
        validator.validate(request);
        NotificationCreationResult result = creationService.createOrLoad(request);
        if (result.deletedAt() != null) {
            return;
        }

        NotificationDeliveryEvent event = new NotificationDeliveryEvent(result.sourceEventId(),
            result.recipientUserId(),
            result.notification());
        stageListener.accept(Stage.DELIVERY);
        deliveryProducer.publishAndAwait(event);
    }

    public enum Stage {
        CREATION,
        DELIVERY
    }
}

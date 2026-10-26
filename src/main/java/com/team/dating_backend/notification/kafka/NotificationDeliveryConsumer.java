package com.team.dating_backend.notification.kafka;

import com.team.dating_backend.notification.dto.event.NotificationDeliveryEvent;
import com.team.dating_backend.notification.service.NotificationDeliveryEventValidator;
import com.team.dating_backend.notification.sse.NotificationEmitterRegistry;
import com.team.dating_backend.notification.sse.NotificationSseObservation;
import com.team.dating_backend.notification.sse.NotificationSseSender;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NotificationDeliveryConsumer {

    private final NotificationDeliveryEventValidator validator;
    private final NotificationSseSender sender;
    private final NotificationEmitterRegistry registry;
    private final NotificationSseObservation observation;

    @KafkaListener(
        id = "notificationDeliveryConsumer",
        idIsGroup = false,
        groupId = "#{notificationSseProcessIdentity.groupId}",
        topics = "${app.notification.kafka.delivery-topic}",
        containerFactory = "notificationDeliveryKafkaListenerContainerFactory",
        autoStartup = "${app.notification.delivery.enabled:false}"
    )
    public void consume(ConsumerRecord<String, NotificationDeliveryEvent> record) {
        observation.deliveryReceived(record);
        long startedAtNanos = System.nanoTime();
        Integer localConnectionCount = null;
        Throwable failure = null;
        try {
            NotificationDeliveryEvent event = record.value();
            validator.validate(event);
            localConnectionCount = registry.findByUserId(event.recipientUserId()).size();
            sender.sendNotification(event.recipientUserId(), event.notification());
        } catch (RuntimeException | Error exception) {
            failure = exception;
            throw exception;
        } finally {
            try {
                observation.deliveryProcessed(record, localConnectionCount, System.nanoTime() - startedAtNanos,
                    failure);
            } catch (Error observationError) {
                if (failure instanceof Error processingError) {
                    if (processingError != observationError) {
                        processingError.addSuppressed(observationError);
                    }
                } else {
                    if (failure != null) {
                        observationError.addSuppressed(failure);
                    }
                    throw observationError;
                }
            }
        }
    }
}

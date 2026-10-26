package com.team.dating_backend.notification.kafka;

import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import com.team.dating_backend.notification.exception.NotificationRetryableCreationException;
import com.team.dating_backend.notification.service.NotificationCreationProcessor;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NotificationCreationConsumer {

    private final NotificationCreationProcessor processor;
    private final NotificationCreationFailureClassifier failureClassifier;

    @KafkaListener(
        id = "notificationCreationConsumer",
        idIsGroup = false,
        groupId = "${app.notification.creation.group-id}",
        topics = "${app.notification.kafka.create-requested-topic}",
        containerFactory = "notificationCreateRequestedKafkaListenerContainerFactory"
    )
    public void consume(ConsumerRecord<String, NotificationCreateRequested> record)
        throws ExecutionException, InterruptedException, TimeoutException {
        try {
            processor.process(record.value());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw exception;
        } catch (ExecutionException | TimeoutException | RuntimeException exception) {
            if (failureClassifier.isRetryable(exception)) {
                throw new NotificationRetryableCreationException(exception);
            }
            throw exception;
        }
    }
}

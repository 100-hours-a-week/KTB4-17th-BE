package com.team.dating_backend.notification.kafka;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.notification.config.NotificationCreationProperties;
import com.team.dating_backend.notification.config.NotificationKafkaProperties;
import com.team.dating_backend.notification.dto.event.NotificationDeliveryEvent;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NotificationDeliveryProducer {

    private final KafkaTemplate<Object, Object> kafkaTemplate;
    private final NotificationKafkaProperties kafkaProperties;
    private final NotificationCreationProperties creationProperties;

    public RecordMetadata publishAndAwait(NotificationDeliveryEvent event)
        throws ExecutionException, InterruptedException, TimeoutException {
        if (event == null
            || event.schemaVersion() != NotificationDeliveryEvent.CURRENT_SCHEMA_VERSION
            || event.sourceEventId() == null
            || event.sourceEventId().isBlank()
            || event.recipientUserId() == null
            || event.recipientUserId() <= 0
            || event.notification() == null
            || event.notification().notificationId() == null
            || event.notification().notificationId() <= 0) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }

        String key = event.recipientUserId().toString();
        var result = kafkaTemplate.send(kafkaProperties.getDeliveryTopic(), key, event);

        try {
            return result.get(creationProperties.getDeliveryResultTimeoutMs(), TimeUnit.MILLISECONDS)
                .getRecordMetadata();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw exception;
        }
    }
}

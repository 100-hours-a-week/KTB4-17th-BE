package com.team.dating_backend.notification.kafka;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.notification.config.NotificationKafkaProperties;
import com.team.dating_backend.notification.config.NotificationPublishProperties;
import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
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
public class NotificationRequestProducer {

    private final KafkaTemplate<Object, Object> kafkaTemplate;
    private final NotificationKafkaProperties kafkaProperties;
    private final NotificationPublishProperties publishProperties;

    public RecordMetadata publishAndAwait(NotificationCreateRequested request)
        throws ExecutionException, InterruptedException, TimeoutException {
        if (request == null || request.recipientUserId() == null) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }

        Long key = request.recipientUserId();
        var result = kafkaTemplate.send(kafkaProperties.getCreateRequestedTopic(), key, request);

        try {
            return result.get(publishProperties.getResultTimeoutMs(), TimeUnit.MILLISECONDS).getRecordMetadata();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw exception;
        }
    }
}

package com.team.dating_backend.notification.kafka;

import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
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

    @KafkaListener(
        id = "notificationCreationConsumer",
        idIsGroup = false,
        topics = "${app.notification.kafka.create-requested-topic}",
        containerFactory = "notificationCreateRequestedKafkaListenerContainerFactory"
    )
    public void consume(ConsumerRecord<String, NotificationCreateRequested> record)
        throws ExecutionException, InterruptedException, TimeoutException {
        processor.process(record.value());

    }
}

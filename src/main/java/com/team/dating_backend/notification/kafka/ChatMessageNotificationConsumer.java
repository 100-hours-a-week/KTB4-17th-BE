package com.team.dating_backend.notification.kafka;

import com.team.dating_backend.chat.dto.event.ChatMessageCreatedKafkaEvent;
import com.team.dating_backend.notification.service.ChatMessageNotificationProcessor;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    prefix = "app.notification.chat-message",
    name = "enabled",
    havingValue = "true"
)
public class ChatMessageNotificationConsumer {

    private final ChatMessageNotificationProcessor processor;

    @KafkaListener(
        id = "chatMessageNotificationConsumer",
        idIsGroup = false,
        groupId = "${app.notification.chat-message.group-id}",
        topics = "${app.chat.kafka.message-created-topic}",
        containerFactory = "notificationChatMessageKafkaListenerContainerFactory"
    )
    public void consume(ConsumerRecord<Long, ChatMessageCreatedKafkaEvent> record)
        throws ExecutionException, InterruptedException, TimeoutException {
        processor.process(record.value());
    }
}

package com.team.dating_backend.chat.service;

import com.team.dating_backend.chat.dto.event.ChatMessageCreatedKafkaEvent;
import java.util.concurrent.CompletableFuture;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

@Component
public class ChatMessageKafkaPublisher {

    private final ChatMessageKafkaEventFactory eventFactory;
    private final KafkaTemplate<Long, ChatMessageCreatedKafkaEvent> kafkaTemplate;
    private final String topic;

    public ChatMessageKafkaPublisher(
        ChatMessageKafkaEventFactory eventFactory,
        KafkaTemplate<Long, ChatMessageCreatedKafkaEvent> kafkaTemplate,
        @Value("${app.chat.kafka.message-created-topic}") String topic) {
        this.eventFactory = eventFactory;
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    public CompletableFuture<SendResult<Long, ChatMessageCreatedKafkaEvent>> publish(
        Long chatMessageId) {
        ChatMessageCreatedKafkaEvent event = eventFactory.create(chatMessageId);
        return kafkaTemplate.send(topic, event.chatRoomId(), event);
    }
}

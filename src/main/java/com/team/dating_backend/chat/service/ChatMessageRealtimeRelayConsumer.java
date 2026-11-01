package com.team.dating_backend.chat.service;

import com.team.dating_backend.chat.dto.event.ChatMessageCreatedKafkaEvent;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    prefix = "app.chat",
    name = {"runtime-enabled", "realtime-relay.enabled"},
    havingValue = "true"
)
public class ChatMessageRealtimeRelayConsumer {

    private final ChatMessageRedisPublisher redisPublisher;

    @KafkaListener(
        id = "chatMessageRealtimeRelayConsumer",
        idIsGroup = false,
        groupId = "${app.chat.realtime-relay.group-id}",
        topics = "${app.chat.kafka.message-created-topic}",
        containerFactory = "chatRealtimeRelayKafkaListenerContainerFactory"
    )
    public void consume(ConsumerRecord<Long, ChatMessageCreatedKafkaEvent> record) {
        redisPublisher.publish(record.value());
    }
}

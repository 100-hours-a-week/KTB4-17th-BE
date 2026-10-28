package com.team.dating_backend.chat.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

import com.team.dating_backend.chat.dto.event.ChatMessageCreatedKafkaEvent;
import com.team.dating_backend.chat.enums.ChatMessageType;
import com.team.dating_backend.chat.enums.ChatRealtimeRelayFailureCode;
import com.team.dating_backend.chat.exception.ChatRealtimeRelayException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;

class ChatMessageRealtimeRelayConsumerTest {

    private ChatMessageRedisPublisher redisPublisher;
    private ChatMessageRealtimeRelayConsumer consumer;
    private ChatMessageCreatedKafkaEvent event;

    @BeforeEach
    void setUp() {
        redisPublisher = org.mockito.Mockito.mock(ChatMessageRedisPublisher.class);
        consumer = new ChatMessageRealtimeRelayConsumer(redisPublisher);
        event = event();
    }

    @Test
    void Kafka_이벤트를_Redis에_발행한다() {
        consumer.consume(new ConsumerRecord<>("chat.message-created.v1", 0, 1L, 30L, event));

        verify(redisPublisher).publish(event);
    }

    @Test
    void Redis_발행에_실패하면_예외를_전달한다() {
        RedisConnectionFailureException failure = new RedisConnectionFailureException(
            ChatRealtimeRelayFailureCode.REDIS_PUBLISH_FAILED.name());
        ChatRealtimeRelayException relayException = new ChatRealtimeRelayException(
            ChatRealtimeRelayFailureCode.REDIS_PUBLISH_FAILED, failure);
        org.mockito.Mockito.doThrow(relayException).when(redisPublisher).publish(event);

        assertThatThrownBy(() -> consumer.consume(
            new ConsumerRecord<>("chat.message-created.v1", 0, 1L, 30L, event)))
            .isSameAs(relayException);
    }

    private ChatMessageCreatedKafkaEvent event() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 10, 28, 10, 0);
        return new ChatMessageCreatedKafkaEvent(
            "chat-message-created:100",
            createdAt,
            30L,
            100L,
            UUID.fromString("d9dd8900-cf39-4bfd-bd8f-feb628016cb4"),
            1L,
            2L,
            List.of(1L, 2L),
            false,
            ChatMessageType.TEXT,
            "안녕하세요",
            null,
            true,
            createdAt);
    }
}

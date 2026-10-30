package com.team.dating_backend.chat.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.team.dating_backend.chat.dto.event.ChatMessageCreatedKafkaEvent;
import com.team.dating_backend.chat.enums.ChatMessageType;
import com.team.dating_backend.chat.enums.ChatRealtimeRelayFailureCode;
import com.team.dating_backend.chat.exception.ChatRealtimeRelayException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

class ChatMessageRedisPublisherTest {

    private static final String CHANNEL = "chat:message-created:v1";
    private static final String PAYLOAD = "{\"eventId\":\"chat-message-created:100\"}";

    private StringRedisTemplate redisTemplate;
    private JsonMapper jsonMapper;
    private ChatMessageRedisPublisher publisher;
    private ChatMessageCreatedKafkaEvent event;

    @BeforeEach
    void setUp() {
        redisTemplate = org.mockito.Mockito.mock(StringRedisTemplate.class);
        jsonMapper = org.mockito.Mockito.mock(JsonMapper.class);
        publisher = new ChatMessageRedisPublisher(redisTemplate, jsonMapper, CHANNEL);
        event = event();
    }

    @Test
    void Kafka_이벤트를_Redis_채널에_발행한다() throws Exception {
        given(jsonMapper.writeValueAsString(event)).willReturn(PAYLOAD);

        publisher.publish(event);

        verify(redisTemplate).convertAndSend(CHANNEL, PAYLOAD);
    }

    @Test
    void Redis_발행_오류를_호출자에게_전달한다() throws Exception {
        given(jsonMapper.writeValueAsString(event)).willReturn(PAYLOAD);
        RedisConnectionFailureException failure = new RedisConnectionFailureException(
            ChatRealtimeRelayFailureCode.REDIS_PUBLISH_FAILED.name());
        given(redisTemplate.convertAndSend(CHANNEL, PAYLOAD)).willThrow(failure);

        assertThatThrownBy(() -> publisher.publish(event))
            .isInstanceOf(ChatRealtimeRelayException.class)
            .hasMessage(ChatRealtimeRelayFailureCode.REDIS_PUBLISH_FAILED.name())
            .hasCause(failure);
    }

    @Test
    void 이벤트를_JSON으로_변환할_수_없으면_실패한다() throws Exception {
        JacksonException failure = org.mockito.Mockito.mock(JacksonException.class);
        given(jsonMapper.writeValueAsString(event)).willThrow(failure);

        assertThatThrownBy(() -> publisher.publish(event))
            .isInstanceOf(ChatRealtimeRelayException.class)
            .hasMessage(ChatRealtimeRelayFailureCode.EVENT_SERIALIZATION_FAILED.name())
            .hasCause(failure);
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

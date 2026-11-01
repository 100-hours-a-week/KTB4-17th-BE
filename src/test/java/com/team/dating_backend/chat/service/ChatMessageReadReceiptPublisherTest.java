package com.team.dating_backend.chat.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.team.dating_backend.chat.dto.event.ChatMessageReadReceiptEvent;
import com.team.dating_backend.chat.dto.event.ChatMessageReadRequestedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

class ChatMessageReadReceiptPublisherTest {

    private static final String CHANNEL = "chat:read-receipt:v1";
    private static final String PAYLOAD = "{\"recipientUserId\":2}";

    private StringRedisTemplate redisTemplate;
    private JsonMapper jsonMapper;
    private ChatMessageReadReceiptPublisher publisher;
    private ChatMessageReadRequestedEvent event;

    @BeforeEach
    void setUp() {
        redisTemplate = org.mockito.Mockito.mock(StringRedisTemplate.class);
        jsonMapper = org.mockito.Mockito.mock(JsonMapper.class);
        publisher = new ChatMessageReadReceiptPublisher(redisTemplate, jsonMapper, CHANNEL);
        event = new ChatMessageReadRequestedEvent(
            2L, new ChatMessageReadReceiptEvent(30L, 1L, 100L));
    }

    @Test
    void 읽음_이벤트를_Redis_채널에_발행한다() throws Exception {
        given(jsonMapper.writeValueAsString(event)).willReturn(PAYLOAD);

        publisher.publish(event);

        verify(redisTemplate).convertAndSend(CHANNEL, PAYLOAD);
    }

    @Test
    void Redis_발행에_실패해도_완료된_읽음_처리는_유지한다() throws Exception {
        given(jsonMapper.writeValueAsString(event)).willReturn(PAYLOAD);
        given(redisTemplate.convertAndSend(CHANNEL, PAYLOAD))
            .willThrow(new RedisConnectionFailureException("REDIS_PUBLISH_FAILED"));

        assertThatCode(() -> publisher.publish(event)).doesNotThrowAnyException();
    }
}

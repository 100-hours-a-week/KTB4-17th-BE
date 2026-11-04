package com.team.dating_backend.aipractice.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;

import com.team.dating_backend.aipractice.dto.event.AiPracticeChatUpdatedEvent;
import com.team.dating_backend.aipractice.enums.AiPracticeChatStatus;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

class AiPracticeRedisEventPublisherTest {

    private static final String CHANNEL = "chat:ai-practice-updated:v1";
    private static final String PAYLOAD = "{\"userId\":1}";

    private StringRedisTemplate redisTemplate;
    private JsonMapper jsonMapper;
    private AiPracticeRedisEventPublisher publisher;
    private AiPracticeChatUpdatedEvent event;

    @BeforeEach
    void setUp() {
        redisTemplate = org.mockito.Mockito.mock(StringRedisTemplate.class);
        jsonMapper = org.mockito.Mockito.mock(JsonMapper.class);
        publisher = new AiPracticeRedisEventPublisher(redisTemplate, jsonMapper, CHANNEL);
        event = new AiPracticeChatUpdatedEvent(
            1L,
            10L,
            100L,
            AiPracticeChatStatus.COMPLETED,
            "응답",
            null,
            LocalDateTime.of(2026, 11, 1, 10, 0));
    }

    @Test
    void Redis_발행에_실패해도_완료된_AI_연습_처리는_유지한다() throws Exception {
        given(jsonMapper.writeValueAsString(event)).willReturn(PAYLOAD);
        given(redisTemplate.convertAndSend(CHANNEL, PAYLOAD))
            .willThrow(new RedisConnectionFailureException("REDIS_PUBLISH_FAILED"));

        assertThatCode(() -> publisher.publish(event)).doesNotThrowAnyException();
    }
}

package com.team.dating_backend.chat.service;

import com.team.dating_backend.chat.dto.event.ChatMessageCreatedKafkaEvent;
import com.team.dating_backend.chat.enums.ChatRealtimeRelayFailureCode;
import com.team.dating_backend.chat.exception.ChatRealtimeRelayException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
@ConditionalOnProperty(
    prefix = "app.chat",
    name = {"runtime-enabled", "realtime-relay.enabled"},
    havingValue = "true"
)
public class ChatMessageRedisPublisher {

    private final StringRedisTemplate redisTemplate;
    private final JsonMapper jsonMapper;
    private final String channel;

    public ChatMessageRedisPublisher(
        StringRedisTemplate redisTemplate,
        JsonMapper jsonMapper,
        @Value("${app.chat.realtime-relay.redis-channel}") String channel) {
        this.redisTemplate = redisTemplate;
        this.jsonMapper = jsonMapper;
        this.channel = channel;
    }

    public void publish(ChatMessageCreatedKafkaEvent event) {
        try {
            redisTemplate.convertAndSend(channel, serialize(event));
        } catch (DataAccessException exception) {
            throw new ChatRealtimeRelayException(
                ChatRealtimeRelayFailureCode.REDIS_PUBLISH_FAILED, exception);
        }
    }

    private String serialize(ChatMessageCreatedKafkaEvent event) {
        try {
            return jsonMapper.writeValueAsString(event);
        } catch (JacksonException exception) {
            throw new ChatRealtimeRelayException(
                ChatRealtimeRelayFailureCode.EVENT_SERIALIZATION_FAILED, exception);
        }
    }
}

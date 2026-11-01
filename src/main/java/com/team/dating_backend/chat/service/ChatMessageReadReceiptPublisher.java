package com.team.dating_backend.chat.service;

import com.team.dating_backend.chat.dto.event.ChatMessageReadRequestedEvent;
import com.team.dating_backend.chat.enums.ChatRealtimeRelayFailureCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Component
@ConditionalOnProperty(
    prefix = "app.chat",
    name = {"runtime-enabled", "realtime-relay.enabled"},
    havingValue = "true"
)
public class ChatMessageReadReceiptPublisher {

    private final StringRedisTemplate redisTemplate;
    private final JsonMapper jsonMapper;
    private final String channel;

    public ChatMessageReadReceiptPublisher(
        StringRedisTemplate redisTemplate,
        JsonMapper jsonMapper,
        @Value("${app.chat.realtime-relay.read-receipt-redis-channel}") String channel) {
        this.redisTemplate = redisTemplate;
        this.jsonMapper = jsonMapper;
        this.channel = channel;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publish(ChatMessageReadRequestedEvent event) {
        try {
            redisTemplate.convertAndSend(channel, jsonMapper.writeValueAsString(event));
        } catch (JacksonException exception) {
            log.warn("failureCode={}, chatRoomId={}, lastReadMessageId={}",
                ChatRealtimeRelayFailureCode.EVENT_SERIALIZATION_FAILED,
                event.receipt().chatRoomId(), event.receipt().lastReadMessageId(), exception);
        } catch (DataAccessException exception) {
            log.warn("failureCode={}, chatRoomId={}, lastReadMessageId={}",
                ChatRealtimeRelayFailureCode.REDIS_PUBLISH_FAILED,
                event.receipt().chatRoomId(), event.receipt().lastReadMessageId(), exception);
        }
    }
}

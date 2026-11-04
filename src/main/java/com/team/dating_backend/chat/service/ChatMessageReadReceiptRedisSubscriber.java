package com.team.dating_backend.chat.service;

import com.team.dating_backend.chat.dto.event.ChatMessageReadRequestedEvent;
import com.team.dating_backend.chat.enums.ChatRealtimeRelayFailureCode;
import com.team.dating_backend.chat.exception.ChatRealtimeRelayException;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
@ConditionalOnProperty(
    prefix = "app.chat",
    name = {"runtime-enabled", "realtime-relay.enabled"},
    havingValue = "true"
)
public class ChatMessageReadReceiptRedisSubscriber implements MessageListener {

    private static final String DESTINATION = "/queue/chat-read-receipts";

    private final JsonMapper jsonMapper;
    private final SimpMessagingTemplate messagingTemplate;

    public ChatMessageReadReceiptRedisSubscriber(
        JsonMapper jsonMapper, SimpMessagingTemplate messagingTemplate) {
        this.jsonMapper = jsonMapper;
        this.messagingTemplate = messagingTemplate;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        ChatMessageReadRequestedEvent event = deserialize(message.getBody());
        try {
            messagingTemplate.convertAndSendToUser(
                event.recipientUserId().toString(), DESTINATION, event.receipt());
        } catch (RuntimeException exception) {
            throw new ChatRealtimeRelayException(
                ChatRealtimeRelayFailureCode.WEBSOCKET_PUBLISH_FAILED, exception);
        }
    }

    private ChatMessageReadRequestedEvent deserialize(byte[] payload) {
        try {
            return jsonMapper.readValue(
                new String(payload, StandardCharsets.UTF_8),
                ChatMessageReadRequestedEvent.class);
        } catch (JacksonException exception) {
            throw new ChatRealtimeRelayException(
                ChatRealtimeRelayFailureCode.EVENT_DESERIALIZATION_FAILED, exception);
        }
    }
}

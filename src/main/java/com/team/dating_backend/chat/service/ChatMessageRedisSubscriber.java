package com.team.dating_backend.chat.service;

import com.team.dating_backend.chat.dto.event.ChatMessageCreatedEvent;
import com.team.dating_backend.chat.dto.event.ChatMessageCreatedKafkaEvent;
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
public class ChatMessageRedisSubscriber implements MessageListener {

    private static final String MESSAGE_DESTINATION = "/queue/chat-messages";

    private final JsonMapper jsonMapper;
    private final SimpMessagingTemplate messagingTemplate;

    public ChatMessageRedisSubscriber(
        JsonMapper jsonMapper, SimpMessagingTemplate messagingTemplate) {
        this.jsonMapper = jsonMapper;
        this.messagingTemplate = messagingTemplate;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        ChatMessageCreatedKafkaEvent event = deserialize(message.getBody());
        for (Long userId : event.realtimeTargetUserIds()) {
            publish(userId, event);
        }
    }

    private ChatMessageCreatedKafkaEvent deserialize(byte[] payload) {
        try {
            return jsonMapper.readValue(
                new String(payload, StandardCharsets.UTF_8),
                ChatMessageCreatedKafkaEvent.class);
        } catch (JacksonException exception) {
            throw new ChatRealtimeRelayException(
                ChatRealtimeRelayFailureCode.EVENT_DESERIALIZATION_FAILED, exception);
        }
    }

    private void publish(Long userId, ChatMessageCreatedKafkaEvent source) {
        boolean isMine = userId.equals(source.senderUserId());
        ChatMessageCreatedEvent event = new ChatMessageCreatedEvent(
            source.chatRoomId(),
            source.messageId(),
            source.clientMessageId(),
            isMine,
            source.messageType(),
            source.textContent(),
            source.imageFileId(),
            isMine && source.isUnreadByReceiver() ? 1 : 0,
            source.createdAt());
        try {
            messagingTemplate.convertAndSendToUser(
                userId.toString(), MESSAGE_DESTINATION, event);
        } catch (RuntimeException exception) {
            throw new ChatRealtimeRelayException(
                ChatRealtimeRelayFailureCode.WEBSOCKET_PUBLISH_FAILED, exception);
        }
    }
}

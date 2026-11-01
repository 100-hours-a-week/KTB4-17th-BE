package com.team.dating_backend.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.team.dating_backend.chat.dto.event.ChatMessageCreatedEvent;
import com.team.dating_backend.chat.dto.event.ChatMessageCreatedKafkaEvent;
import com.team.dating_backend.chat.enums.ChatMessageType;
import com.team.dating_backend.chat.enums.ChatRealtimeRelayFailureCode;
import com.team.dating_backend.chat.exception.ChatRealtimeRelayException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

class ChatMessageRedisSubscriberTest {

    private static final String DESTINATION = "/queue/chat-messages";
    private static final byte[] PAYLOAD = "{}".getBytes(StandardCharsets.UTF_8);
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 10, 28, 10, 0);

    private JsonMapper jsonMapper;
    private SimpMessagingTemplate messagingTemplate;
    private ChatMessageRedisSubscriber subscriber;
    private Message message;

    @BeforeEach
    void setUp() {
        jsonMapper = org.mockito.Mockito.mock(JsonMapper.class);
        messagingTemplate = org.mockito.Mockito.mock(SimpMessagingTemplate.class);
        subscriber = new ChatMessageRedisSubscriber(jsonMapper, messagingTemplate);
        message = org.mockito.Mockito.mock(Message.class);
        given(message.getBody()).willReturn(PAYLOAD);
    }

    @Test
    void Redis_이벤트를_발신자와_수신자에게_전달한다() throws Exception {
        given(jsonMapper.readValue("{}", ChatMessageCreatedKafkaEvent.class))
            .willReturn(event(List.of(1L, 2L), true));
        ArgumentCaptor<String> userIdCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<ChatMessageCreatedEvent> eventCaptor = ArgumentCaptor.forClass(
            ChatMessageCreatedEvent.class);

        subscriber.onMessage(message, null);

        verify(messagingTemplate, times(2)).convertAndSendToUser(
            userIdCaptor.capture(), eq(DESTINATION), eventCaptor.capture());
        assertThat(userIdCaptor.getAllValues()).containsExactly("1", "2");
        assertThat(eventCaptor.getAllValues())
            .extracting(ChatMessageCreatedEvent::mine)
            .containsExactly(true, false);
        assertThat(eventCaptor.getAllValues())
            .extracting(ChatMessageCreatedEvent::unreadCount)
            .containsExactly(1, 0);
    }

    @Test
    void 지정된_사용자에게만_이벤트를_전달한다() throws Exception {
        given(jsonMapper.readValue("{}", ChatMessageCreatedKafkaEvent.class))
            .willReturn(event(List.of(2L), true));

        subscriber.onMessage(message, null);

        verify(messagingTemplate).convertAndSendToUser(
            eq("2"), eq(DESTINATION), any(ChatMessageCreatedEvent.class));
    }

    @Test
    void 수신자가_이미_읽은_경우_발신자의_안_읽은_메시지_수는_증가하지_않는다() throws Exception {
        given(jsonMapper.readValue("{}", ChatMessageCreatedKafkaEvent.class))
            .willReturn(event(List.of(1L), false));
        ArgumentCaptor<ChatMessageCreatedEvent> eventCaptor = ArgumentCaptor.forClass(
            ChatMessageCreatedEvent.class);

        subscriber.onMessage(message, null);

        verify(messagingTemplate).convertAndSendToUser(
            eq("1"), eq(DESTINATION), eventCaptor.capture());
        assertThat(eventCaptor.getValue().unreadCount()).isZero();
    }

    @Test
    void JSON_변환에_실패하면_오류_코드를_전달한다() throws Exception {
        JacksonException failure = org.mockito.Mockito.mock(JacksonException.class);
        given(jsonMapper.readValue("{}", ChatMessageCreatedKafkaEvent.class))
            .willThrow(failure);

        assertThatThrownBy(() -> subscriber.onMessage(message, null))
            .isInstanceOf(ChatRealtimeRelayException.class)
            .hasMessage(ChatRealtimeRelayFailureCode.EVENT_DESERIALIZATION_FAILED.name())
            .hasCause(failure);
    }

    @Test
    void WebSocket_전송에_실패하면_오류_코드를_전달한다() throws Exception {
        ChatMessageCreatedKafkaEvent event = event(List.of(1L), true);
        given(jsonMapper.readValue("{}", ChatMessageCreatedKafkaEvent.class))
            .willReturn(event);
        MessageDeliveryException failure = new MessageDeliveryException(
            ChatRealtimeRelayFailureCode.WEBSOCKET_PUBLISH_FAILED.name());
        doThrow(failure).when(messagingTemplate).convertAndSendToUser(
            eq("1"), eq(DESTINATION), any(ChatMessageCreatedEvent.class));

        assertThatThrownBy(() -> subscriber.onMessage(message, null))
            .isInstanceOf(ChatRealtimeRelayException.class)
            .hasMessage(ChatRealtimeRelayFailureCode.WEBSOCKET_PUBLISH_FAILED.name())
            .hasCause(failure);
    }

    private ChatMessageCreatedKafkaEvent event(
        List<Long> targetUserIds, boolean isUnreadByReceiver) {
        return new ChatMessageCreatedKafkaEvent(
            "chat-message-created:100",
            CREATED_AT,
            30L,
            100L,
            UUID.fromString("d9dd8900-cf39-4bfd-bd8f-feb628016cb4"),
            1L,
            2L,
            targetUserIds,
            false,
            ChatMessageType.TEXT,
            "안녕하세요",
            null,
            isUnreadByReceiver,
            CREATED_AT);
    }
}

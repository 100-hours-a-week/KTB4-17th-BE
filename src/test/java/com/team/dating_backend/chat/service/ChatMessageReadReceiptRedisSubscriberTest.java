package com.team.dating_backend.chat.service;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.team.dating_backend.chat.dto.event.ChatMessageReadReceiptEvent;
import com.team.dating_backend.chat.dto.event.ChatMessageReadRequestedEvent;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.Message;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import tools.jackson.databind.json.JsonMapper;

class ChatMessageReadReceiptRedisSubscriberTest {

    private static final String DESTINATION = "/queue/chat-read-receipts";
    private static final byte[] PAYLOAD = "{}".getBytes(StandardCharsets.UTF_8);

    private JsonMapper jsonMapper;
    private SimpMessagingTemplate messagingTemplate;
    private ChatMessageReadReceiptRedisSubscriber subscriber;
    private Message message;

    @BeforeEach
    void setUp() {
        jsonMapper = org.mockito.Mockito.mock(JsonMapper.class);
        messagingTemplate = org.mockito.Mockito.mock(SimpMessagingTemplate.class);
        subscriber = new ChatMessageReadReceiptRedisSubscriber(jsonMapper, messagingTemplate);
        message = org.mockito.Mockito.mock(Message.class);
        given(message.getBody()).willReturn(PAYLOAD);
    }

    @Test
    void Redis에서_받은_읽음_이벤트를_대상_사용자에게_전달한다() throws Exception {
        ChatMessageReadReceiptEvent receipt = new ChatMessageReadReceiptEvent(30L, 1L, 100L);
        ChatMessageReadRequestedEvent event = new ChatMessageReadRequestedEvent(2L, receipt);
        given(jsonMapper.readValue("{}", ChatMessageReadRequestedEvent.class)).willReturn(event);

        subscriber.onMessage(message, null);

        verify(messagingTemplate).convertAndSendToUser("2", DESTINATION, receipt);
    }
}

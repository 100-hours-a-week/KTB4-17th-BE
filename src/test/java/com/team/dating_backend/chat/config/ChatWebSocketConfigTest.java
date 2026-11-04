package com.team.dating_backend.chat.config;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.team.dating_backend.auth.service.JwtService;
import com.team.dating_backend.security.config.SecurityProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;

class ChatWebSocketConfigTest {

    @Test
    @SuppressWarnings("unchecked")
    void WebSocket_메시지를_발행한_순서대로_전송한다() {
        ObjectProvider<MessageChannel> clientOutboundChannel = mock(ObjectProvider.class);
        ChatWebSocketConfig configuration = new ChatWebSocketConfig(
            mock(SecurityProperties.class),
            mock(JwtService.class),
            clientOutboundChannel);
        MessageBrokerRegistry registry = mock(MessageBrokerRegistry.class);

        configuration.configureMessageBroker(registry);

        verify(registry).enableSimpleBroker("/queue");
        verify(registry).setPreservePublishOrder(true);
        verify(registry).setApplicationDestinationPrefixes("/app");
        verify(registry).setUserDestinationPrefix("/user");
    }
}

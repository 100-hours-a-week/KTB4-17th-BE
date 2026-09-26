package com.team.dating_backend.chat.config;

import com.team.dating_backend.security.ServiceAuthenticationPrincipal;
import com.team.dating_backend.security.config.SecurityProperties;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.core.Authentication;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class ChatWebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private static final Set<String> USER_SUBSCRIPTIONS = Set.of(
        "/user/queue/chat-messages",
        "/user/queue/chat-room-updates");

    private final SecurityProperties securityProperties;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/chat")
            .setAllowedOrigins(
                securityProperties.getAllowedOrigins().toArray(String[]::new));
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/queue");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(
                    message, StompHeaderAccessor.class);
                if (accessor == null) {
                    return null;
                }

                StompCommand command = accessor.getCommand();
                if (command == StompCommand.SEND) {
                    return null;
                }

                if (command == StompCommand.CONNECT || command == StompCommand.STOMP
                    || command == StompCommand.SUBSCRIBE) {
                    if (!(accessor.getUser() instanceof Authentication authentication)
                        || !authentication.isAuthenticated()
                        || !(authentication.getPrincipal() instanceof ServiceAuthenticationPrincipal)) {
                        return null;
                    }
                }

                if (command == StompCommand.SUBSCRIBE) {
                    String destination = accessor.getDestination();
                    if (destination == null || !USER_SUBSCRIPTIONS.contains(destination)) {
                        return null;
                    }
                }

                return message;
            }
        });
    }
}

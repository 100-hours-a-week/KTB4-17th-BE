package com.team.dating_backend.chat.config;

import com.team.dating_backend.auth.service.JwtService;
import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.security.BearerTokenResolver;
import com.team.dating_backend.security.ServiceAuthenticationPrincipal;
import com.team.dating_backend.security.config.SecurityProperties;
import io.jsonwebtoken.JwtException;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@ConditionalOnProperty(prefix = "app.chat", name = "runtime-enabled", havingValue = "true")
@EnableWebSocketMessageBroker
public class ChatWebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private static final String PRESENCE_DESTINATION = "/app/chat-rooms/presence";
    private static final Set<String> USER_SUBSCRIPTIONS = Set.of(
        "/user/queue/chat-messages",
        "/user/queue/chat-read-receipts",
        "/user/queue/chat-room-updates",
        "/user/queue/ai-practice");

    private final SecurityProperties securityProperties;
    private final JwtService jwtService;
    private final ObjectProvider<MessageChannel> clientOutboundChannel;

    public ChatWebSocketConfig(
        SecurityProperties securityProperties,
        JwtService jwtService,
        @Qualifier("clientOutboundChannel") ObjectProvider<MessageChannel> clientOutboundChannel) {
        this.securityProperties = securityProperties;
        this.jwtService = jwtService;
        this.clientOutboundChannel = clientOutboundChannel;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.setPreserveReceiveOrder(true);
        registry.addEndpoint("/ws/chat")
            .setAllowedOrigins(
                securityProperties.getAllowedOrigins().toArray(String[]::new));
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/queue");
        registry.setApplicationDestinationPrefixes("/app");
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
                if (command == StompCommand.CONNECT || command == StompCommand.STOMP) {
                    try {
                        authenticate(accessor);
                    } catch (MessageDeliveryException exception) {
                        StompHeaderAccessor error = StompHeaderAccessor.create(StompCommand.ERROR);
                        error.setSessionId(accessor.getSessionId());
                        error.setMessage(exception.getMessage());
                        error.setLeaveMutable(true);
                        clientOutboundChannel.getObject().send(
                            MessageBuilder.createMessage(new byte[0], error.getMessageHeaders()));
                        return null;
                    }
                }

                if (command == StompCommand.CONNECT || command == StompCommand.STOMP
                    || command == StompCommand.SUBSCRIBE || command == StompCommand.SEND) {
                    if (!(accessor.getUser() instanceof Authentication authentication)
                        || !authentication.isAuthenticated()
                        || !(authentication
                            .getPrincipal() instanceof ServiceAuthenticationPrincipal)) {
                        return null;
                    }
                }

                if (command == StompCommand.SEND
                    && !PRESENCE_DESTINATION.equals(accessor.getDestination())) {
                    return null;
                }

                if (command == StompCommand.SUBSCRIBE) {
                    String destination = accessor.getDestination();
                    if (destination == null || !USER_SUBSCRIPTIONS.contains(destination)) {
                        return null;
                    }
                }

                return message;
            }

            private void authenticate(StompHeaderAccessor accessor) {
                String bearerToken = BearerTokenResolver.resolve(
                    accessor.getFirstNativeHeader(HttpHeaders.AUTHORIZATION));
                accessor.removeNativeHeader(HttpHeaders.AUTHORIZATION);

                if (bearerToken == null) {
                    throw new MessageDeliveryException(CommonErrorCode.AUTH_REQUIRED.name());
                }

                try {
                    Long userId = jwtService.parseServiceAuthToken(bearerToken);
                    accessor.setUser(UsernamePasswordAuthenticationToken.authenticated(
                        new ServiceAuthenticationPrincipal(userId), null, Set.of()));
                } catch (JwtException exception) {
                    throw new MessageDeliveryException(CommonErrorCode.AUTH_REQUIRED.name());
                }
            }
        });
    }
}

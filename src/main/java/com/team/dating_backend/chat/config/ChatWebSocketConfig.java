package com.team.dating_backend.chat.config;

import com.team.dating_backend.auth.service.JwtService;
import com.team.dating_backend.chat.websocket.RabbitUserDestinations;
import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.security.BearerTokenResolver;
import com.team.dating_backend.security.ServiceAuthenticationPrincipal;
import com.team.dating_backend.security.config.SecurityProperties;
import io.jsonwebtoken.JwtException;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
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
@EnableWebSocketMessageBroker
public class ChatWebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private static final String PRESENCE_DESTINATION = "/app/chat-rooms/presence";

    private final SecurityProperties securityProperties;
    private final JwtService jwtService;
    private final ChatRabbitProperties rabbitProperties;
    private final RabbitUserDestinations destinations;
    private final ObjectProvider<MessageChannel> clientOutboundChannel;

    public ChatWebSocketConfig(
        SecurityProperties securityProperties,
        JwtService jwtService,
        ChatRabbitProperties rabbitProperties,
        RabbitUserDestinations destinations,
        @Qualifier("clientOutboundChannel") ObjectProvider<MessageChannel> clientOutboundChannel) {
        this.securityProperties = securityProperties;
        this.jwtService = jwtService;
        this.rabbitProperties = rabbitProperties;
        this.destinations = destinations;
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
        // 접속 서버가 달라도 채팅을 전달하도록 공통 RabbitMQ로 중계한다.
        registry.enableStompBrokerRelay("/exchange")
            .setRelayHost(rabbitProperties.getHost())
            .setRelayPort(rabbitProperties.getStompPort())
            .setClientLogin(rabbitProperties.getUsername())
            .setClientPasscode(rabbitProperties.getPassword())
            .setSystemLogin(rabbitProperties.getUsername())
            .setSystemPasscode(rabbitProperties.getPassword())
            .setVirtualHost(rabbitProperties.getVirtualHost())
            .setSystemHeartbeatSendInterval(10000)
            .setSystemHeartbeatReceiveInterval(10000);
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
                    if (destination == null || !RabbitUserDestinations.USER_SUBSCRIPTIONS.contains(destination)) {
                        return null;
                    }
                    Authentication authentication = (Authentication) accessor.getUser();
                    ServiceAuthenticationPrincipal principal = (ServiceAuthenticationPrincipal) authentication
                        .getPrincipal();
                    // 다른 사용자의 큐를 구독하지 못하도록 인증된 사용자로 경로를 결정한다.
                    for (String header : Set.copyOf(accessor.toNativeHeaderMap().keySet())) {
                        if (!Set.of("id", "destination", "receipt").contains(header)) {
                            accessor.removeNativeHeader(header);
                        }
                    }
                    accessor.setDestination(destinations.brokerDestination(
                        principal.getName(), destination));
                    accessor.setNativeHeader("ack", "auto");
                    // 연결이 끝난 큐는 남기지 않고 미수신 메시지는 기존 이력 조회로 복구한다.
                    accessor.setNativeHeader("durable", "false");
                    accessor.setNativeHeader("auto-delete", "true");
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

    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
                if (accessor.getCommand() == StompCommand.MESSAGE) {
                    // 기존 클라이언트가 구독 경로를 바꾸지 않도록 응답 경로를 복원한다.
                    String destination = destinations.clientDestination(accessor.getDestination());
                    if (destination != null) {
                        accessor.setDestination(destination);
                        return MessageBuilder.createMessage(
                            message.getPayload(), accessor.getMessageHeaders());
                    }
                }
                return message;
            }
        });
    }
}

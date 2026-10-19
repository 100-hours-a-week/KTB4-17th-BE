package com.team.dating_backend.chat.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.team.dating_backend.auth.service.JwtService;
import com.team.dating_backend.chat.websocket.RabbitUserDestinations;
import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.security.ServiceAuthenticationPrincipal;
import com.team.dating_backend.security.config.SecurityProperties;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class ChatWebSocketConfigTest {

    private final ChatRabbitProperties properties = new ChatRabbitProperties();
    private final ObjectProvider<MessageChannel> outboundChannel = mock(ObjectProvider.class);
    private final ChatWebSocketConfig config = new ChatWebSocketConfig(
        new SecurityProperties(), mock(JwtService.class), properties,
        new RabbitUserDestinations(properties), outboundChannel);

    @Test
    void 사용자_구독을_본인_라우팅키로_바꾸고_큐_조작_헤더를_제거한다() {
        ChannelRegistration registration = mock(ChannelRegistration.class);
        config.configureClientInboundChannel(registration);
        ArgumentCaptor<ChannelInterceptor> captor = ArgumentCaptor.forClass(ChannelInterceptor.class);
        verify(registration).interceptors(captor.capture());
        StompHeaderAccessor headers = subscription("/user/queue/chat-messages");
        headers.setNativeHeader("x-queue-name", "another-user-queue");
        headers.setNativeHeader("durable", "true");
        headers.setNativeHeader("x-dead-letter-exchange", "other");
        Message<byte[]> message = message(headers);

        assertThat(captor.getValue().preSend(message, null)).isNotNull();
        assertThat(headers.getDestination()).isEqualTo("/exchange/ecs.chat.events/user.42.chat-messages");
        assertThat(headers.getFirstNativeHeader("x-queue-name")).isNull();
        assertThat(headers.getFirstNativeHeader("x-dead-letter-exchange")).isNull();
        assertThat(headers.getFirstNativeHeader("durable")).isEqualTo("false");
        assertThat(headers.getFirstNativeHeader("ack")).isEqualTo("auto");
    }

    @Test
    void 다른_사용자의_RabbitMQ_경로로_직접_구독할_수_없다() {
        ChannelRegistration registration = mock(ChannelRegistration.class);
        config.configureClientInboundChannel(registration);
        ArgumentCaptor<ChannelInterceptor> captor = ArgumentCaptor.forClass(ChannelInterceptor.class);
        verify(registration).interceptors(captor.capture());

        assertThat(captor.getValue().preSend(message(subscription(
            "/exchange/ecs.chat.events/user.99.chat-messages")), null)).isNull();
    }

    @Test
    void 수신_프레임에는_기존_프런트엔드_구독경로를_복원한다() {
        ChannelRegistration registration = mock(ChannelRegistration.class);
        config.configureClientOutboundChannel(registration);
        ArgumentCaptor<ChannelInterceptor> captor = ArgumentCaptor.forClass(ChannelInterceptor.class);
        verify(registration).interceptors(captor.capture());
        StompHeaderAccessor headers = StompHeaderAccessor.create(StompCommand.MESSAGE);
        headers.setDestination("/exchange/ecs.chat.events/user.42.chat-messages");
        headers.setSubscriptionId("subscription-1");

        Message<?> result = captor.getValue().preSend(message(headers), null);
        StompHeaderAccessor received = StompHeaderAccessor.wrap(result);
        assertThat(received.getDestination()).isEqualTo("/user/queue/chat-messages");
        assertThat(received.getSubscriptionId()).isEqualTo("subscription-1");
    }

    @Test
    void 인증된_접속상태_SEND만_기존_컨트롤러로_전달한다() {
        ChannelInterceptor interceptor = inboundInterceptor();
        StompHeaderAccessor headers = authenticated(StompCommand.SEND, "/app/chat-rooms/presence");
        Message<byte[]> message = message(headers);

        assertThat(interceptor.preSend(message, null)).isSameAs(message);
        assertThat(headers.getDestination()).isEqualTo("/app/chat-rooms/presence");
    }

    @Test
    void 인증되어도_다른_SEND_경로는_차단한다() {
        ChannelInterceptor interceptor = inboundInterceptor();
        for (String destination : Set.of(
            "/app/other", "/queue/chat-messages", "/user/queue/chat-messages",
            "/exchange/ecs.chat.events/user.99.chat-messages")) {
            StompHeaderAccessor headers = authenticated(StompCommand.SEND, destination);
            assertThat(interceptor.preSend(message(headers), null)).isNull();
        }
    }

    @Test
    void 인증되지_않으면_접속상태_SEND와_구독을_차단한다() {
        ChannelInterceptor interceptor = inboundInterceptor();
        StompHeaderAccessor send = StompHeaderAccessor.create(StompCommand.SEND);
        send.setDestination("/app/chat-rooms/presence");
        StompHeaderAccessor subscribe = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        subscribe.setDestination("/user/queue/chat-messages");

        assertThat(interceptor.preSend(message(send), null)).isNull();
        assertThat(interceptor.preSend(message(subscribe), null)).isNull();
    }

    @Test
    void 토큰이_없는_CONNECT에는_기존처럼_ERROR를_보낸다() {
        MessageChannel outbound = mock(MessageChannel.class);
        when(outboundChannel.getObject()).thenReturn(outbound);
        StompHeaderAccessor connect = StompHeaderAccessor.create(StompCommand.CONNECT);
        connect.setSessionId("session-42");

        assertThat(inboundInterceptor().preSend(message(connect), null)).isNull();
        ArgumentCaptor<Message> error = ArgumentCaptor.forClass(Message.class);
        verify(outbound).send(error.capture());
        StompHeaderAccessor received = StompHeaderAccessor.wrap(error.getValue());
        assertThat(received.getCommand()).isEqualTo(StompCommand.ERROR);
        assertThat(received.getSessionId()).isEqualTo("session-42");
        assertThat(received.getMessage()).isEqualTo(CommonErrorCode.AUTH_REQUIRED.name());
    }

    private ChannelInterceptor inboundInterceptor() {
        ChannelRegistration registration = mock(ChannelRegistration.class);
        config.configureClientInboundChannel(registration);
        ArgumentCaptor<ChannelInterceptor> captor = ArgumentCaptor.forClass(ChannelInterceptor.class);
        verify(registration).interceptors(captor.capture());
        return captor.getValue();
    }

    private StompHeaderAccessor subscription(String destination) {
        return authenticated(StompCommand.SUBSCRIBE, destination);
    }

    private StompHeaderAccessor authenticated(StompCommand command, String destination) {
        StompHeaderAccessor headers = StompHeaderAccessor.create(command);
        headers.setDestination(destination);
        headers.setUser(UsernamePasswordAuthenticationToken.authenticated(
            new ServiceAuthenticationPrincipal(42L), null, Set.of()));
        return headers;
    }

    private Message<byte[]> message(StompHeaderAccessor headers) {
        headers.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
    }
}

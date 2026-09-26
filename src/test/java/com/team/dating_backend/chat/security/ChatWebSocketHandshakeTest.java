package com.team.dating_backend.chat.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import com.team.dating_backend.auth.config.JwtProperties;
import com.team.dating_backend.auth.controller.AuthCookieFactory;
import com.team.dating_backend.auth.service.JwtService;
import com.team.dating_backend.chat.config.ChatWebSocketConfig;
import com.team.dating_backend.chat.dto.event.ChatMessageCreatedEvent;
import com.team.dating_backend.chat.enums.ChatMessageType;
import com.team.dating_backend.security.ApiAccessDeniedHandler;
import com.team.dating_backend.security.ApiAuthenticationEntryPoint;
import com.team.dating_backend.security.ServiceAuthenticationPrincipal;
import com.team.dating_backend.security.config.SecurityConfig;
import com.team.dating_backend.security.config.SecurityProperties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.security.core.Authentication;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import org.springframework.web.socket.messaging.WebSocketStompClient;

@SpringBootTest(classes = ChatWebSocketHandshakeTest.TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.config.name=chat-websocket-test",
    "server.address=127.0.0.1",
    "app.security.allowed-origins=http://localhost:5173",
    "jwt.secret=RsJrP89+FuXm/BZZiqI2p8tCi1PAvb0/rATGDAp0KX0=",
    "jwt.service-expiration-minutes=60"
})
class ChatWebSocketHandshakeTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private BlockingQueue<SessionConnectEvent> sessionConnectEvents;

    @Autowired
    private BlockingQueue<SessionSubscribeEvent> sessionSubscribeEvents;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @Autowired
    private SimpUserRegistry simpUserRegistry;

    @Test
    void 유효한_ACCESS_TOKEN이면_STOMP에_인증사용자가_전달된다() throws Exception {
        String token = jwtService.createServiceAuthToken(42L);

        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.add(
            HttpHeaders.COOKIE,
            AuthCookieFactory.ACCESS_TOKEN_COOKIE + "=" + token);
        headers.setOrigin("http://localhost:5173");
        headers.setSecWebSocketProtocol("v12.stomp");

        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        StompSession session = null;

        try {
            session = client.connectAsync(
                "ws://127.0.0.1:" + port + "/ws/chat",
                headers,
                new StompSessionHandlerAdapter() {})
                .get(5, TimeUnit.SECONDS);

            assertTrue(session.isConnected());

            SessionConnectEvent event = sessionConnectEvents.poll(5, TimeUnit.SECONDS);
            assertNotNull(event, "STOMP CONNECT 이벤트가 발생해야 합니다.");

            Authentication authentication = assertInstanceOf(Authentication.class, event.getUser());
            ServiceAuthenticationPrincipal principal = assertInstanceOf(
                ServiceAuthenticationPrincipal.class,
                authentication.getPrincipal());

            assertTrue(authentication.isAuthenticated());
            assertEquals(42L, principal.userId());
        } finally {
            if (session != null && session.isConnected()) {
                session.disconnect();
            }
            client.stop();
        }
    }

    @Test
    void 인증된_사용자가_구독한_user_destination으로_메시지_이벤트가_전달된다() throws Exception {
        String token = jwtService.createServiceAuthToken(42L);

        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.add(
            HttpHeaders.COOKIE,
            AuthCookieFactory.ACCESS_TOKEN_COOKIE + "=" + token);
        headers.setOrigin("http://localhost:5173");
        headers.setSecWebSocketProtocol("v12.stomp");

        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new JacksonJsonMessageConverter());
        StompSession session = null;
        BlockingQueue<ChatMessageCreatedEvent> receivedEvents = new LinkedBlockingQueue<>();
        UUID clientMessageId = UUID.randomUUID();
        ChatMessageCreatedEvent expected = new ChatMessageCreatedEvent(
            30L,
            500L,
            clientMessageId,
            false,
            ChatMessageType.TEXT,
            "안녕하세요",
            LocalDateTime.of(2026, 9, 27, 10, 0));
        try {
            session = client.connectAsync(
                "ws://127.0.0.1:" + port + "/ws/chat",
                headers,
                new StompSessionHandlerAdapter() {})
                .get(5, TimeUnit.SECONDS);
            session.subscribe("/user/queue/chat-messages", new StompFrameHandler() {
                @Override
                public java.lang.reflect.Type getPayloadType(StompHeaders headers) {
                    return ChatMessageCreatedEvent.class;
                }

                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    receivedEvents.add((ChatMessageCreatedEvent) payload);
                }
            });
            assertNotNull(sessionSubscribeEvents.poll(5, TimeUnit.SECONDS));
            assertTrue(awaitUserSubscription("42", "/user/queue/chat-messages"));
            assertEquals("/user/", messagingTemplate.getUserDestinationPrefix());

            messagingTemplate.convertAndSendToUser(
                "42", "/queue/chat-messages", expected);
            ChatMessageCreatedEvent receivedEvent = receivedEvents.poll(5, TimeUnit.SECONDS);
            assertEquals(expected, receivedEvent);
        } finally {
            if (session != null && session.isConnected()) {
                session.disconnect();
            }
            client.stop();
        }
    }

    @Test
    void ACCESS_TOKEN이_없으면_handshake가_거부된다() throws Exception {
        assertHandshakeRejected(null, "http://localhost:5173");
    }

    @Test
    void 유효하지_않은_ACCESS_TOKEN이면_handshake가_거부된다() throws Exception {
        assertHandshakeRejected("invalid-token", "http://localhost:5173");
    }

    @Test
    void 허용되지_않은_Origin이면_handshake가_거부된다() throws Exception {
        String token = jwtService.createServiceAuthToken(42L);

        assertHandshakeRejected(token, "https://attacker.example");
    }

    private void assertHandshakeRejected(String token, String origin) throws Exception {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();

        if (token != null) {
            headers.add(
                HttpHeaders.COOKIE,
                AuthCookieFactory.ACCESS_TOKEN_COOKIE + "=" + token);
        }

        headers.setOrigin(origin);
        headers.setSecWebSocketProtocol("v12.stomp");

        StandardWebSocketClient client = new StandardWebSocketClient();

        assertThrows(ExecutionException.class, () -> client.execute(
            new TextWebSocketHandler(),
            headers,
            URI.create("ws://127.0.0.1:" + port + "/ws/chat"))
            .get(5, TimeUnit.SECONDS));
    }

    private boolean awaitUserSubscription(String userName, String destination)
        throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            SimpUser user = simpUserRegistry.getUser(userName);
            if (user != null && user.getSessions().stream()
                .flatMap(session -> session.getSubscriptions().stream())
                .anyMatch(subscription -> destination.equals(subscription.getDestination()))) {
                return true;
            }
            Thread.sleep(10);
        }
        return false;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {
        DataSourceAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class
    })
    @EnableConfigurationProperties({
        JwtProperties.class,
        SecurityProperties.class
    })
    @Import({
        SecurityConfig.class,
        ChatWebSocketConfig.class,
        JwtService.class,
        ApiAuthenticationEntryPoint.class,
        ApiAccessDeniedHandler.class,
        ConnectEventTestConfiguration.class
    })
    static class TestApplication {}

    @TestConfiguration(proxyBeanMethods = false)
    static class ConnectEventTestConfiguration {

        @Bean
        BlockingQueue<SessionConnectEvent> sessionConnectEvents() {
            return new LinkedBlockingQueue<>();
        }

        @Bean
        BlockingQueue<SessionSubscribeEvent> sessionSubscribeEvents() {
            return new LinkedBlockingQueue<>();
        }

        @Bean
        ApplicationListener<SessionConnectEvent> sessionConnectEventListener(
            BlockingQueue<SessionConnectEvent> sessionConnectEvents) {
            return sessionConnectEvents::add;
        }

        @Bean
        ApplicationListener<SessionSubscribeEvent> sessionSubscribeEventListener(
            BlockingQueue<SessionSubscribeEvent> sessionSubscribeEvents) {
            return sessionSubscribeEvents::add;
        }
    }
}

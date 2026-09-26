package com.team.dating_backend.chat.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.team.dating_backend.auth.config.JwtProperties;
import com.team.dating_backend.auth.controller.AuthCookieFactory;
import com.team.dating_backend.auth.service.JwtService;
import com.team.dating_backend.chat.config.ChatWebSocketConfig;
import com.team.dating_backend.chat.controller.ChatExceptionHandler;
import com.team.dating_backend.chat.controller.ChatMessageListController;
import com.team.dating_backend.chat.controller.ChatMessageSendController;
import com.team.dating_backend.chat.dto.event.ChatMessageCreatedEvent;
import com.team.dating_backend.chat.enums.ChatMessageType;
import com.team.dating_backend.chat.service.ChatMessageListService;
import com.team.dating_backend.chat.service.ChatMessageOutboxPublishJob;
import com.team.dating_backend.chat.service.ChatMessageRateLimiter;
import com.team.dating_backend.chat.service.ChatMessageSendService;
import com.team.dating_backend.chat.service.ChatRoomParticipantDisplayService;
import com.team.dating_backend.security.ApiAccessDeniedHandler;
import com.team.dating_backend.security.ApiAuthenticationEntryPoint;
import com.team.dating_backend.security.config.SecurityConfig;
import com.team.dating_backend.security.config.SecurityProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@SpringBootTest(classes = ChatMessageFlowIntegrationTest.TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.config.name=chat-message-flow-test",
    "server.address=127.0.0.1",
    "spring.jpa.hibernate.ddl-auto=create",
    "app.security.allowed-origins=http://localhost:5173",
    "jwt.secret=RsJrP89+FuXm/BZZiqI2p8tCi1PAvb0/rATGDAp0KX0=",
    "jwt.service-expiration-minutes=60"
})
@Testcontainers(disabledWithoutDocker = true)
class ChatMessageFlowIntegrationTest {

    private static final Long CHAT_ROOM_ID = 83001L;
    private static final Long SENDER_USER_ID = 83011L;
    private static final Long RECEIVER_USER_ID = 83012L;
    private static final String CSRF_TOKEN = "chat-integration-csrf-token";
    private static final String MESSAGE_DESTINATION = "/user/queue/chat-messages";
    private static final String MESSAGE_TEXT = "실제 DB 통합 테스트 메시지";
    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 9, 27, 10, 0);
    private static final SecureRandom CSRF_RANDOM = new SecureRandom();

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private ChatMessageOutboxPublishJob outboxPublishJob;

    @Autowired
    private SimpUserRegistry simpUserRegistry;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @BeforeEach
    void setUpDatabase() {
        jdbcTemplate.update("delete from chat_message_outbox");
        jdbcTemplate.update("delete from chat_messages");
        jdbcTemplate.update("delete from chat_participants");
        jdbcTemplate.update("delete from chat_rooms");
        jdbcTemplate.update("delete from profiles where user_id in (?, ?)",
            SENDER_USER_ID, RECEIVER_USER_ID);
        jdbcTemplate.update("delete from users where id in (?, ?)",
            SENDER_USER_ID, RECEIVER_USER_ID);

        insertUser(SENDER_USER_ID, "sender", "보내는사람");
        insertUser(RECEIVER_USER_ID, "receiver", "받는사람");

        jdbcTemplate.update(
            "insert into chat_rooms (id, match_id, status, created_at) values (?, ?, ?, ?)",
            CHAT_ROOM_ID, 83021L, "ACTIVE", Timestamp.valueOf(BASE_TIME));
        jdbcTemplate.update(
            "insert into chat_participants (chat_room_id, user_id, status, is_chat_notification) "
                + "values (?, ?, ?, ?)",
            CHAT_ROOM_ID, SENDER_USER_ID, "ACTIVE", true);
        jdbcTemplate.update(
            "insert into chat_participants (chat_room_id, user_id, status, is_chat_notification) "
                + "values (?, ?, ?, ?)",
            CHAT_ROOM_ID, RECEIVER_USER_ID, "ACTIVE", true);
    }

    @Test
    void HTTP_전송부터_DB_저장_Outbox_WebSocket_전달_이력_조회까지_연결한다() throws Exception {
        UUID clientMessageId = UUID.randomUUID();
        String senderToken = jwtService.createServiceAuthToken(SENDER_USER_ID);
        String receiverToken = jwtService.createServiceAuthToken(RECEIVER_USER_ID);
        BlockingQueue<ChatMessageCreatedEvent> senderEvents = new LinkedBlockingQueue<>();
        BlockingQueue<ChatMessageCreatedEvent> receiverEvents = new LinkedBlockingQueue<>();
        WebSocketStompClient stompClient = new WebSocketStompClient(
            new StandardWebSocketClient());
        stompClient.setMessageConverter(new JacksonJsonMessageConverter());
        StompSession senderSession = null;
        StompSession receiverSession = null;

        try {
            senderSession = connectAndSubscribe(
                stompClient, senderToken, senderEvents);
            receiverSession = connectAndSubscribe(
                stompClient, receiverToken, receiverEvents);

            assertTrue(awaitUserSubscription(SENDER_USER_ID.toString(), MESSAGE_DESTINATION));
            assertTrue(awaitUserSubscription(RECEIVER_USER_ID.toString(), MESSAGE_DESTINATION));

            HttpResponse<String> sent = postMessage(senderToken, clientMessageId, MESSAGE_TEXT);
            assertEquals(201, sent.statusCode(), sent.body());

            Long messageId = jdbcTemplate.queryForObject(
                "select id from chat_messages where chat_room_id = ? "
                    + "and client_message_id = UNHEX(REPLACE(?, '-', ''))",
                Long.class, CHAT_ROOM_ID, clientMessageId.toString());
            assertNotNull(messageId);
            assertEquals(1, countMessages());
            assertEquals(1, countOutboxes());
            assertThat(sent.body()).contains("\"messageId\":" + messageId);

            HttpResponse<String> retry = postMessage(senderToken, clientMessageId, MESSAGE_TEXT);
            assertEquals(201, retry.statusCode());
            assertThat(retry.body()).contains("\"messageId\":" + messageId);
            assertEquals(1, countMessages());
            assertEquals(1, countOutboxes());

            HttpResponse<String> conflict = postMessage(
                senderToken, clientMessageId, "같은 ID의 다른 내용");
            assertEquals(409, conflict.statusCode());
            assertEquals(1, countMessages());
            assertEquals(1, countOutboxes());

            outboxPublishJob.publishDueMessages();

            ChatMessageCreatedEvent senderEvent = senderEvents.poll(5, TimeUnit.SECONDS);
            ChatMessageCreatedEvent receiverEvent = receiverEvents.poll(5, TimeUnit.SECONDS);
            assertNotNull(senderEvent);
            assertNotNull(receiverEvent);
            assertThat(senderEvent.chatRoomId()).isEqualTo(CHAT_ROOM_ID);
            assertThat(senderEvent.messageId()).isEqualTo(messageId);
            assertThat(senderEvent.clientMessageId()).isEqualTo(clientMessageId);
            assertThat(senderEvent.mine()).isTrue();
            assertThat(senderEvent.textContent()).isEqualTo(MESSAGE_TEXT);
            assertThat(receiverEvent.chatRoomId()).isEqualTo(CHAT_ROOM_ID);
            assertThat(receiverEvent.messageId()).isEqualTo(messageId);
            assertThat(receiverEvent.mine()).isFalse();
            assertThat(receiverEvent.textContent()).isEqualTo(MESSAGE_TEXT);
            assertEquals(1, countPublishedOutboxes());

            HttpResponse<String> senderHistory = getMessageHistory(senderToken);
            HttpResponse<String> receiverHistory = getMessageHistory(receiverToken);
            assertEquals(200, senderHistory.statusCode());
            assertEquals(200, receiverHistory.statusCode());
            assertThat(senderHistory.body())
                .contains("\"chatRoomId\":" + CHAT_ROOM_ID)
                .contains("\"nickname\":\"받는사람\"")
                .contains("\"messageId\":" + messageId)
                .contains("\"mine\":true")
                .contains("\"messageType\":\"TEXT\"")
                .contains("\"textContent\":\"" + MESSAGE_TEXT + "\"");
            assertThat(receiverHistory.body())
                .contains("\"nickname\":\"보내는사람\"")
                .contains("\"messageId\":" + messageId)
                .contains("\"mine\":false")
                .contains("\"textContent\":\"" + MESSAGE_TEXT + "\"");
        } finally {
            disconnect(senderSession);
            disconnect(receiverSession);
            stompClient.stop();
        }
    }

    private StompSession connectAndSubscribe(
        WebSocketStompClient stompClient,
        String accessToken,
        BlockingQueue<ChatMessageCreatedEvent> receivedEvents) throws Exception {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.add(HttpHeaders.COOKIE,
            AuthCookieFactory.ACCESS_TOKEN_COOKIE + "=" + accessToken);
        headers.setOrigin("http://localhost:5173");
        headers.setSecWebSocketProtocol("v12.stomp");

        StompSession session = stompClient.connectAsync(
            "ws://127.0.0.1:" + port + "/ws/chat",
            headers,
            new StompSessionHandlerAdapter() {})
            .get(5, TimeUnit.SECONDS);
        session.subscribe(MESSAGE_DESTINATION, new StompFrameHandler() {
            @Override
            public java.lang.reflect.Type getPayloadType(StompHeaders stompHeaders) {
                return ChatMessageCreatedEvent.class;
            }

            @Override
            public void handleFrame(StompHeaders stompHeaders, Object payload) {
                receivedEvents.add((ChatMessageCreatedEvent) payload);
            }
        });
        return session;
    }

    private HttpResponse<String> postMessage(
        String accessToken, UUID clientMessageId, String textContent) throws Exception {
        String body = "{\"clientMessageId\":\"" + clientMessageId
            + "\",\"messageType\":\"" + ChatMessageType.TEXT
            + "\",\"textContent\":\"" + textContent + "\"}";
        HttpRequest request = HttpRequest.newBuilder(messageUri())
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .header(HttpHeaders.COOKIE, cookieHeader(accessToken, true))
            .header("X-XSRF-TOKEN", maskedCsrfToken())
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getMessageHistory(String accessToken) throws Exception {
        URI uri = URI.create(messageUri() + "?size=20");
        HttpRequest request = HttpRequest.newBuilder(uri)
            .header(HttpHeaders.COOKIE, cookieHeader(accessToken, false))
            .GET()
            .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String cookieHeader(String accessToken, boolean includeCsrf) {
        String accessCookie = AuthCookieFactory.ACCESS_TOKEN_COOKIE + "=" + accessToken;
        return includeCsrf ? accessCookie + "; XSRF-TOKEN=" + CSRF_TOKEN : accessCookie;
    }

    private String maskedCsrfToken() {
        byte[] tokenBytes = CSRF_TOKEN.getBytes(StandardCharsets.UTF_8);
        byte[] randomBytes = new byte[tokenBytes.length];
        CSRF_RANDOM.nextBytes(randomBytes);
        byte[] maskedBytes = new byte[tokenBytes.length * 2];
        System.arraycopy(randomBytes, 0, maskedBytes, 0, randomBytes.length);
        for (int index = 0; index < tokenBytes.length; index++) {
            maskedBytes[randomBytes.length + index] = (byte) (randomBytes[index] ^ tokenBytes[index]);
        }
        return Base64.getUrlEncoder().encodeToString(maskedBytes);
    }

    private URI messageUri() {
        return URI.create("http://127.0.0.1:" + port
            + "/api/v1/chat-rooms/" + CHAT_ROOM_ID + "/messages");
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

    private int countMessages() {
        return jdbcTemplate.queryForObject(
            "select count(*) from chat_messages where chat_room_id = ?",
            Integer.class, CHAT_ROOM_ID);
    }

    private int countOutboxes() {
        return jdbcTemplate.queryForObject(
            "select count(*) from chat_message_outbox outbox "
                + "join chat_messages message on message.id = outbox.chat_message_id "
                + "where message.chat_room_id = ?",
            Integer.class, CHAT_ROOM_ID);
    }

    private int countPublishedOutboxes() {
        return jdbcTemplate.queryForObject(
            "select count(*) from chat_message_outbox outbox "
                + "join chat_messages message on message.id = outbox.chat_message_id "
                + "where message.chat_room_id = ? and outbox.published_at is not null",
            Integer.class, CHAT_ROOM_ID);
    }

    private void insertUser(Long userId, String name, String nickname) {
        jdbcTemplate.update(
            "insert into users (id, status, name, birth_date, gender, face_verification_status, "
                + "last_accessed_at, created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            userId, "ACTIVE", name, LocalDate.of(1995, 1, 1), "MALE", "VERIFIED",
            Timestamp.valueOf(BASE_TIME), Timestamp.valueOf(BASE_TIME), Timestamp.valueOf(BASE_TIME));
        jdbcTemplate.update(
            "insert into profiles (user_id, nickname, created_at, updated_at) values (?, ?, ?, ?)",
            userId, nickname, Timestamp.valueOf(BASE_TIME), Timestamp.valueOf(BASE_TIME));
    }

    private void disconnect(StompSession session) {
        if (session != null && session.isConnected()) {
            session.disconnect();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EntityScan(basePackages = "com.team.dating_backend")
    @EnableJpaRepositories(basePackages = {
        "com.team.dating_backend.chat.repository",
        "com.team.dating_backend.user.repository"
    })
    @EnableConfigurationProperties({JwtProperties.class, SecurityProperties.class})
    @Import({
        SecurityConfig.class,
        ChatWebSocketConfig.class,
        ChatMessageSendController.class,
        ChatMessageListController.class,
        ChatExceptionHandler.class,
        ChatMessageSendService.class,
        ChatMessageListService.class,
        ChatRoomParticipantDisplayService.class,
        ChatMessageRateLimiter.class,
        ChatMessageOutboxPublishJob.class,
        JwtService.class,
        ApiAuthenticationEntryPoint.class,
        ApiAccessDeniedHandler.class
    })
    static class TestApplication {}
}

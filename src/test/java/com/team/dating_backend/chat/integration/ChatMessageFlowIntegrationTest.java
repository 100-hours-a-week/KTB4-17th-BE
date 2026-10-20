package com.team.dating_backend.chat.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;

import com.team.dating_backend.TestJwtSecret;
import com.team.dating_backend.auth.config.JwtProperties;
import com.team.dating_backend.auth.service.JwtService;
import com.team.dating_backend.chat.config.ChatWebSocketConfig;
import com.team.dating_backend.chat.controller.ChatExceptionHandler;
import com.team.dating_backend.chat.controller.ChatMessageListController;
import com.team.dating_backend.chat.controller.ChatMessageSendController;
import com.team.dating_backend.chat.controller.ChatRoomPresenceController;
import com.team.dating_backend.chat.dto.request.ChatRoomPresenceRequest;
import com.team.dating_backend.chat.enums.ChatRoomPresenceAction;
import com.team.dating_backend.chat.presence.ChatRoomPresenceDisconnectListener;
import com.team.dating_backend.chat.presence.ChatRoomPresenceRegistry;
import com.team.dating_backend.chat.service.ChatRoomPresenceService;
import com.team.dating_backend.notification.service.ChatNotificationReadService;
import com.team.dating_backend.notification.service.NotificationCreateService;
import com.team.dating_backend.chat.dto.event.ChatMessageCreatedEvent;
import com.team.dating_backend.chat.dto.event.ChatMessageReadReceiptEvent;
import com.team.dating_backend.chat.enums.ChatMessageType;
import com.team.dating_backend.chat.controller.ChatMessageReadController;
import com.team.dating_backend.chat.service.ChatMessageListService;
import com.team.dating_backend.chat.service.ChatMessageOutboxPublishJob;
import com.team.dating_backend.chat.service.ChatMessageReadReceiptPublisher;
import com.team.dating_backend.chat.service.ChatMessageReadService;
import com.team.dating_backend.chat.service.ChatMessageRateLimiter;
import com.team.dating_backend.chat.service.ChatMessageSendService;
import com.team.dating_backend.chat.service.ChatRoomParticipantDisplayService;
import com.team.dating_backend.file.repository.JpaFileRepositoryAdapter;
import com.team.dating_backend.profile.service.ProfileImageGetService;
import com.team.dating_backend.security.ApiAccessDeniedHandler;
import com.team.dating_backend.security.ApiAuthenticationEntryPoint;
import com.team.dating_backend.security.UserAuthenticationService;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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

@SpringBootTest(
    classes = ChatMessageFlowIntegrationTest.TestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.config.name=chat-message-flow-test",
        "server.address=127.0.0.1",
        "app.chat.runtime-enabled=true",
        "spring.jpa.hibernate.ddl-auto=create",
        "spring.flyway.enabled=false",
        "app.security.allowed-origins=http://localhost:5173",
        "jwt.service-expiration-minutes=60"
    }
)
@Testcontainers(disabledWithoutDocker = true)
class ChatMessageFlowIntegrationTest {

    private static final String TEST_JWT_SECRET = TestJwtSecret.generate();
    private static final Long CHAT_ROOM_ID = 83001L;
    private static final Long SENDER_USER_ID = 83011L;
    private static final Long RECEIVER_USER_ID = 83012L;
    private static final String MESSAGE_DESTINATION = "/user/queue/chat-messages";
    private static final String READ_RECEIPT_DESTINATION = "/user/queue/chat-read-receipts";
    private static final String MESSAGE_TEXT = "실제 DB 통합 테스트 메시지";
    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 9, 27, 10, 0);

    @DynamicPropertySource
    static void registerJwtSecret(DynamicPropertyRegistry registry) {
        registry.add("jwt.secret", () -> TEST_JWT_SECRET);
    }

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

    @Autowired
    private ChatRoomPresenceRegistry presenceRegistry;

    @MockitoSpyBean
    private ChatRoomPresenceService presenceService;

    @MockitoBean
    private ChatMessageRateLimiter rateLimiter;

    @MockitoBean
    private ProfileImageGetService profileImageGetService;

    @MockitoBean
    private UserAuthenticationService userAuthenticationService;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final BlockingQueue<ChatRoomPresenceRequest> completedPresenceRequests = new LinkedBlockingQueue<>();

    @BeforeEach
    void setUpDatabase() {
        given(profileImageGetService.getProfileImagesByUserIds(anyCollection()))
            .willReturn(Map.of());
        given(userAuthenticationService.isAuthenticationAllowed(anyLong())).willReturn(true);
        given(rateLimiter.tryAcquire(anyLong())).willReturn(true);
        completedPresenceRequests.clear();
        doAnswer(invocation -> {
            try {
                return invocation.callRealMethod();
            } finally {
                completedPresenceRequests.add(invocation.getArgument(2));
            }
        }).when(presenceService).updatePresence(anyLong(), anyString(), any(), anyMap());

        jdbcTemplate.update("delete from notifications");
        jdbcTemplate.update("delete from chat_message_outbox");
        jdbcTemplate.update("delete from chat_messages");
        jdbcTemplate.update("delete from files");
        jdbcTemplate.update("delete from chat_participants");
        jdbcTemplate.update("delete from chat_rooms");
        jdbcTemplate.update("delete from matches where id = ?", 83021L);
        jdbcTemplate.update("delete from profiles where user_id in (?, ?)",
            SENDER_USER_ID, RECEIVER_USER_ID);
        jdbcTemplate.update("delete from users where id in (?, ?)",
            SENDER_USER_ID, RECEIVER_USER_ID);

        insertUser(SENDER_USER_ID, "sender", "보내는사람");
        insertUser(RECEIVER_USER_ID, "receiver", "받는사람");

        jdbcTemplate.update(
            "insert into matches (id, sender_id, receiver_id, status, matched_at) "
                + "values (?, ?, ?, ?, ?)",
            83021L, SENDER_USER_ID, RECEIVER_USER_ID, "ACTIVE", Timestamp.valueOf(BASE_TIME));
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
        BlockingQueue<ChatMessageReadReceiptEvent> readReceiptEvents = new LinkedBlockingQueue<>();
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
            subscribeToReadReceipts(senderSession, readReceiptEvents);

            assertTrue(awaitUserSubscription(SENDER_USER_ID.toString(), MESSAGE_DESTINATION));
            assertTrue(awaitUserSubscription(RECEIVER_USER_ID.toString(), MESSAGE_DESTINATION));
            assertTrue(awaitUserSubscription(
                SENDER_USER_ID.toString(), READ_RECEIPT_DESTINATION));

            HttpResponse<String> sent = postMessage(senderToken, clientMessageId, MESSAGE_TEXT);
            assertEquals(201, sent.statusCode(), sent.body());

            Long messageId = jdbcTemplate.queryForObject(
                "select id from chat_messages where chat_room_id = ? "
                    + "and client_message_id = UNHEX(REPLACE(?, '-', ''))",
                Long.class, CHAT_ROOM_ID, clientMessageId.toString());
            assertNotNull(messageId);
            assertEquals(1, countMessages());
            assertEquals(1, countOutboxes());
            assertEquals(1, countChatNotifications());
            assertEquals("보내는사람님에게서 메시지가 도착했어요.", jdbcTemplate.queryForObject(
                "select body from notifications where user_id = ? and type = 'CHAT_MESSAGE'",
                String.class, RECEIVER_USER_ID));
            assertThat(sent.body()).contains("\"messageId\":" + messageId);

            HttpResponse<String> retry = postMessage(senderToken, clientMessageId, MESSAGE_TEXT);
            assertEquals(201, retry.statusCode());
            assertThat(retry.body()).contains("\"messageId\":" + messageId);
            assertEquals(1, countMessages());
            assertEquals(1, countOutboxes());
            assertEquals(1, countChatNotifications());

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
            assertThat(senderEvent.unreadCount()).isEqualTo(1);
            assertThat(receiverEvent.chatRoomId()).isEqualTo(CHAT_ROOM_ID);
            assertThat(receiverEvent.messageId()).isEqualTo(messageId);
            assertThat(receiverEvent.mine()).isFalse();
            assertThat(receiverEvent.textContent()).isEqualTo(MESSAGE_TEXT);
            assertThat(receiverEvent.unreadCount()).isZero();
            assertEquals(1, countPublishedOutboxes());

            HttpResponse<String> senderHistoryBeforeRead = getMessageHistory(senderToken);
            assertThat(senderHistoryBeforeRead.body()).contains("\"unreadCount\":1");

            HttpResponse<String> secondMessage = postMessage(
                senderToken, UUID.randomUUID(), "두 번째 메시지");
            assertEquals(201, secondMessage.statusCode(), secondMessage.body());
            assertEquals(2, countChatNotifications());

            HttpResponse<String> read = postRead(receiverToken, messageId);
            assertEquals(200, read.statusCode(), read.body());
            assertThat(read.body()).contains("\"lastReadMessageId\":" + messageId);
            assertEquals(0, jdbcTemplate.queryForObject(
                "select count(*) from notifications where user_id = ? "
                    + "and type = 'CHAT_MESSAGE' and read_at is null",
                Integer.class, RECEIVER_USER_ID));

            ChatMessageReadReceiptEvent readReceipt = readReceiptEvents.poll(5, TimeUnit.SECONDS);
            assertNotNull(readReceipt);
            assertThat(readReceipt.chatRoomId()).isEqualTo(CHAT_ROOM_ID);
            assertThat(readReceipt.readerUserId()).isEqualTo(RECEIVER_USER_ID);
            assertThat(readReceipt.lastReadMessageId()).isEqualTo(messageId);

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
                .contains("\"textContent\":\"" + MESSAGE_TEXT + "\"")
                .contains("\"unreadCount\":0");
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

    @Test
    void 이미지_메시지는_소유한_파일만_연결하고_WebSocket과_이력에_fileId를_전달한다()
        throws Exception {
        Long fileId = 83031L;
        insertImageFile(fileId);
        String senderToken = jwtService.createServiceAuthToken(SENDER_USER_ID);
        String receiverToken = jwtService.createServiceAuthToken(RECEIVER_USER_ID);
        BlockingQueue<ChatMessageCreatedEvent> receiverEvents = new LinkedBlockingQueue<>();
        WebSocketStompClient stompClient = new WebSocketStompClient(
            new StandardWebSocketClient());
        stompClient.setMessageConverter(new JacksonJsonMessageConverter());
        StompSession receiverSession = null;

        try {
            receiverSession = connectAndSubscribe(stompClient, receiverToken, receiverEvents);
            assertTrue(awaitUserSubscription(RECEIVER_USER_ID.toString(), MESSAGE_DESTINATION));

            HttpResponse<String> sent = postImageMessage(
                senderToken, UUID.randomUUID(), fileId);
            assertEquals(201, sent.statusCode(), sent.body());
            Long messageId = jdbcTemplate.queryForObject(
                "select id from chat_messages where chat_room_id = ? and file_id = ?",
                Long.class, CHAT_ROOM_ID, fileId);
            assertNotNull(messageId);
            assertEquals("IMAGE", jdbcTemplate.queryForObject(
                "select message_type from chat_messages where id = ?", String.class, messageId));
            assertEquals(1, countChatNotifications());
            assertEquals("보내는사람님에게서 메시지가 도착했어요.", jdbcTemplate.queryForObject(
                "select body from notifications where user_id = ? and type = 'CHAT_MESSAGE'",
                String.class, RECEIVER_USER_ID));

            outboxPublishJob.publishDueMessages();
            ChatMessageCreatedEvent event = receiverEvents.poll(5, TimeUnit.SECONDS);
            assertNotNull(event);
            assertThat(event.messageType()).isEqualTo(ChatMessageType.IMAGE);
            assertThat(event.imageFileId()).isEqualTo(fileId);
            assertThat(event.unreadCount()).isZero();

            HttpResponse<String> history = getMessageHistory(receiverToken);
            assertEquals(200, history.statusCode());
            assertThat(history.body())
                .contains("\"messageType\":\"IMAGE\"")
                .contains("\"imageFileId\":" + fileId);
        } finally {
            disconnect(receiverSession);
            stompClient.stop();
        }
    }

    @Test
    void 여러_연결의_화면_상태와_연결_종료에_따라_알림을_생략하거나_생성한다() throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new JacksonJsonMessageConverter());
        String receiverToken = jwtService.createServiceAuthToken(RECEIVER_USER_ID);
        String senderToken = jwtService.createServiceAuthToken(SENDER_USER_ID);
        StompSession pc = null;
        StompSession mobile = null;
        try {
            pc = connectAndSubscribe(client, receiverToken, new LinkedBlockingQueue<>());
            mobile = connectAndSubscribe(client, receiverToken, new LinkedBlockingQueue<>());
            sendPresenceAndAwait(pc, ChatRoomPresenceAction.ENTER);
            sendPresenceAndAwait(mobile, ChatRoomPresenceAction.ENTER);
            assertEquals(201, postMessage(senderToken, UUID.randomUUID(), "첫 번째").statusCode());
            assertEquals(0, countChatNotifications());

            disconnect(pc);
            assertTrue(awaitCondition(() -> {
                SimpUser user = simpUserRegistry.getUser(RECEIVER_USER_ID.toString());
                return user != null && user.getSessions().size() == 1;
            }));
            assertThat(presenceRegistry.isViewingChatRoom(RECEIVER_USER_ID, CHAT_ROOM_ID)).isTrue();
            assertEquals(201, postMessage(senderToken, UUID.randomUUID(), "두 번째").statusCode());
            assertEquals(0, countChatNotifications());

            sendPresenceAndAwait(mobile, ChatRoomPresenceAction.LEAVE);
            assertEquals(201, postMessage(senderToken, UUID.randomUUID(), "세 번째").statusCode());
            assertEquals(1, countChatNotifications());

            sendPresenceAndAwait(mobile, ChatRoomPresenceAction.ENTER);
            disconnect(mobile);
            assertTrue(awaitCondition(() -> !presenceRegistry.isViewingChatRoom(
                RECEIVER_USER_ID, CHAT_ROOM_ID)));
            assertEquals(201, postMessage(senderToken, UUID.randomUUID(), "네 번째").statusCode());
            assertEquals(2, countChatNotifications());
        } finally {
            disconnect(pc);
            disconnect(mobile);
            client.stop();
        }
    }

    @Test
    void 같은_연결의_입장_검증이_느려도_뒤따르는_이탈을_수신_순서대로_처리한다() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch leaveStarted = new CountDownLatch(1);
        CountDownLatch left = new CountDownLatch(1);
        doAnswer(invocation -> {
            ChatRoomPresenceRequest request = invocation.getArgument(2);
            if (request.action() == ChatRoomPresenceAction.ENTER) {
                entered.countDown();
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            } else if (request.action() == ChatRoomPresenceAction.LEAVE) {
                leaveStarted.countDown();
            }
            Object result = invocation.callRealMethod();
            if (request.action() == ChatRoomPresenceAction.LEAVE) {
                left.countDown();
            }
            return result;
        }).when(presenceService).updatePresence(anyLong(), anyString(), any(), anyMap());
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new JacksonJsonMessageConverter());
        StompSession session = null;
        try {
            session = connectAndSubscribe(client,
                jwtService.createServiceAuthToken(RECEIVER_USER_ID), new LinkedBlockingQueue<>());
            session.send("/app/chat-rooms/presence",
                new ChatRoomPresenceRequest(CHAT_ROOM_ID, ChatRoomPresenceAction.ENTER));
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            session.send("/app/chat-rooms/presence",
                new ChatRoomPresenceRequest(CHAT_ROOM_ID, ChatRoomPresenceAction.LEAVE));
            assertThat(leaveStarted.await(150, TimeUnit.MILLISECONDS)).isFalse();
            release.countDown();
            assertThat(left.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(presenceRegistry.isViewingChatRoom(RECEIVER_USER_ID, CHAT_ROOM_ID)).isFalse();
        } finally {
            release.countDown();
            disconnect(session);
            client.stop();
        }
    }

    @Test
    void 알림_생성이_실패하면_메시지와_Outbox도_함께_롤백한다() throws Exception {
        jdbcTemplate.execute("ALTER TABLE notifications ADD CONSTRAINT test_reject_chat_notification "
            + "CHECK (type <> 'CHAT_MESSAGE')");
        try {
            HttpResponse<String> response = postMessage(
                jwtService.createServiceAuthToken(SENDER_USER_ID), UUID.randomUUID(), MESSAGE_TEXT);

            assertEquals(500, response.statusCode(), response.body());
            assertEquals(0, countMessages());
            assertEquals(0, countOutboxes());
            assertEquals(0, countChatNotifications());
        } finally {
            jdbcTemplate.execute("ALTER TABLE notifications DROP CHECK test_reject_chat_notification");
        }
    }

    @Test
    void 알림_읽음_갱신이_실패하면_채팅_읽음_위치도_롤백한다() throws Exception {
        assertEquals(201, postMessage(jwtService.createServiceAuthToken(SENDER_USER_ID),
            UUID.randomUUID(), MESSAGE_TEXT).statusCode());
        Long messageId = jdbcTemplate.queryForObject(
            "select id from chat_messages where chat_room_id = ?", Long.class, CHAT_ROOM_ID);
        jdbcTemplate.execute("ALTER TABLE notifications ADD CONSTRAINT test_reject_chat_read "
            + "CHECK (type <> 'CHAT_MESSAGE' OR read_at IS NULL)");
        try {
            HttpResponse<String> response = postRead(
                jwtService.createServiceAuthToken(RECEIVER_USER_ID), messageId);

            assertEquals(500, response.statusCode(), response.body());
            assertThat(jdbcTemplate.queryForObject(
                "select last_read_message_id from chat_participants "
                    + "where chat_room_id = ? and user_id = ?",
                Long.class, CHAT_ROOM_ID, RECEIVER_USER_ID)).isNull();
            assertThat(jdbcTemplate.queryForObject(
                "select read_at from notifications where user_id = ? and type = 'CHAT_MESSAGE'",
                Timestamp.class, RECEIVER_USER_ID)).isNull();
        } finally {
            jdbcTemplate.execute("ALTER TABLE notifications DROP CHECK test_reject_chat_read");
        }
    }

    private void sendPresenceAndAwait(StompSession session, ChatRoomPresenceAction action)
        throws InterruptedException {
        ChatRoomPresenceRequest request = new ChatRoomPresenceRequest(CHAT_ROOM_ID, action);
        session.send("/app/chat-rooms/presence", request);
        assertThat(completedPresenceRequests.poll(3, TimeUnit.SECONDS)).isEqualTo(request);
    }

    private boolean awaitCondition(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10);
        }
        return false;
    }

    private int countChatNotifications() {
        return jdbcTemplate.queryForObject(
            "select count(*) from notifications where type = 'CHAT_MESSAGE'", Integer.class);
    }

    private StompSession connectAndSubscribe(
        WebSocketStompClient stompClient,
        String accessToken,
        BlockingQueue<ChatMessageCreatedEvent> receivedEvents) throws Exception {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.setOrigin("http://localhost:5173");
        headers.setSecWebSocketProtocol("v12.stomp");
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);

        StompSession session = stompClient.connectAsync(
            "ws://127.0.0.1:" + port + "/ws/chat",
            headers,
            connectHeaders,
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

    private void subscribeToReadReceipts(
        StompSession session,
        BlockingQueue<ChatMessageReadReceiptEvent> receivedEvents) {
        session.subscribe(READ_RECEIPT_DESTINATION, new StompFrameHandler() {
            @Override
            public java.lang.reflect.Type getPayloadType(StompHeaders stompHeaders) {
                return ChatMessageReadReceiptEvent.class;
            }

            @Override
            public void handleFrame(StompHeaders stompHeaders, Object payload) {
                receivedEvents.add((ChatMessageReadReceiptEvent) payload);
            }
        });
    }

    private HttpResponse<String> postMessage(
        String accessToken, UUID clientMessageId, String textContent) throws Exception {
        String body = "{\"clientMessageId\":\"" + clientMessageId
            + "\",\"messageType\":\"" + ChatMessageType.TEXT
            + "\",\"textContent\":\"" + textContent + "\"}";
        HttpRequest request = HttpRequest.newBuilder(messageUri())
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postImageMessage(
        String accessToken, UUID clientMessageId, Long imageFileId) throws Exception {
        String body = "{\"clientMessageId\":\"" + clientMessageId
            + "\",\"messageType\":\"IMAGE\",\"textContent\":null,\"imageFileId\":"
            + imageFileId + "}";
        HttpRequest request = HttpRequest.newBuilder(messageUri())
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getMessageHistory(String accessToken) throws Exception {
        URI uri = URI.create(messageUri() + "?size=20");
        HttpRequest request = HttpRequest.newBuilder(uri)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
            .GET()
            .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postRead(String accessToken, Long lastReadMessageId)
        throws Exception {
        String body = "{\"lastReadMessageId\":" + lastReadMessageId + "}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(
            "http://127.0.0.1:" + port + "/api/v1/chat-rooms/" + CHAT_ROOM_ID + "/read"))
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
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
            Timestamp.valueOf(BASE_TIME), Timestamp.valueOf(BASE_TIME),
            Timestamp.valueOf(BASE_TIME));
        jdbcTemplate.update(
            "insert into profiles (user_id, nickname, created_at, updated_at) values (?, ?, ?, ?)",
            userId, nickname, Timestamp.valueOf(BASE_TIME), Timestamp.valueOf(BASE_TIME));
    }

    private void insertImageFile(Long fileId) {
        jdbcTemplate.update(
            "insert into files (id, owner_user_id, storage_key, original_name, mime_type, "
                + "file_size, created_at, deleted_at) values (?, ?, ?, ?, ?, ?, ?, null)",
            fileId, SENDER_USER_ID, "files/chat-image.jpg", "chat-image.jpg", "image/jpeg",
            1024L, Timestamp.valueOf(BASE_TIME));
    }

    private void disconnect(StompSession session) {
        if (session != null && session.isConnected()) {
            session.disconnect();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EntityScan(basePackages = "com.team.dating_backend")
    @EnableJpaRepositories(
        basePackages = {
            "com.team.dating_backend.chat.repository",
            "com.team.dating_backend.user.repository",
            "com.team.dating_backend.notification.repository",
            "com.team.dating_backend.profile.repository",
            "com.team.dating_backend.file.repository"
        }
    )
    @EnableConfigurationProperties({JwtProperties.class, SecurityProperties.class})
    @Import(
        {
            SecurityConfig.class,
            ChatWebSocketConfig.class,
            ChatRoomPresenceController.class,
            ChatRoomPresenceService.class,
            ChatRoomPresenceRegistry.class,
            ChatRoomPresenceDisconnectListener.class,
            NotificationCreateService.class,
            ChatNotificationReadService.class,
            ChatMessageSendController.class,
            ChatMessageListController.class,
            ChatMessageReadController.class,
            ChatExceptionHandler.class,
            ChatMessageSendService.class,
            ChatMessageListService.class,
            ChatMessageReadService.class,
            ChatMessageReadReceiptPublisher.class,
            ChatRoomParticipantDisplayService.class,
            JpaFileRepositoryAdapter.class,
            ChatMessageRateLimiter.class,
            ChatMessageOutboxPublishJob.class,
            JwtService.class,
            ApiAuthenticationEntryPoint.class,
            ApiAccessDeniedHandler.class
        }
    )
    static class TestApplication {}
}

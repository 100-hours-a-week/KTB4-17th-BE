package com.team.dating_backend.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.team.dating_backend.auth.service.JwtService;
import com.team.dating_backend.common.exception.GlobalExceptionHandler;
import com.team.dating_backend.notification.controller.NotificationController;
import com.team.dating_backend.notification.cursor.NotificationCursorCodec;
import com.team.dating_backend.notification.dto.read.NotificationCursor;
import com.team.dating_backend.notification.entity.Notification;
import com.team.dating_backend.notification.enums.NotificationCategory;
import com.team.dating_backend.notification.enums.NotificationType;
import com.team.dating_backend.notification.repository.NotificationRepository;
import com.team.dating_backend.notification.service.NotificationAccessService;
import com.team.dating_backend.notification.service.NotificationDeleteService;
import com.team.dating_backend.notification.service.NotificationListService;
import com.team.dating_backend.notification.service.NotificationReadService;
import com.team.dating_backend.notification.service.NotificationUnreadCountService;
import com.team.dating_backend.security.ApiAccessDeniedHandler;
import com.team.dating_backend.security.ApiAuthenticationEntryPoint;
import com.team.dating_backend.security.UserAuthenticationService;
import com.team.dating_backend.security.config.SecurityConfig;
import com.team.dating_backend.security.config.SecurityProperties;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.Gender;
import com.team.dating_backend.user.repository.UserRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
    classes = NotificationListIntegrationTest.TestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.config.name=notification-list-test",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.open-in-view=false",
        "server.address=127.0.0.1",
        "app.security.allowed-origins=http://localhost:5173"
    }
)
@Testcontainers
class NotificationListIntegrationTest {

    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 10, 24, 12, 0, 0, 123_456_000);

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @LocalServerPort
    private int port;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private NotificationCursorCodec cursorCodec;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private JwtService jwtService;

    private TransactionTemplate transaction;
    private HttpClient client;
    private Long userId;
    private Long otherUserId;
    private long sourceSequence;

    @BeforeEach
    void setUp() {
        transaction = new TransactionTemplate(transactionManager);
        userId = activeUser("요청회원");
        otherUserId = activeUser("다른회원");
        given(jwtService.parseServiceAuthToken("list-token")).willReturn(userId);
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    @AfterEach
    void tearDown() {
        client.shutdownNow();
        jdbc.update("DELETE FROM notifications WHERE user_id IN (?, ?)", userId, otherUserId);
        jdbc.update("DELETE FROM users WHERE id IN (?, ?)", userId, otherUserId);
    }

    @ParameterizedTest
    @EnumSource(NotificationCategory.class)
    void 응답_커서를_그대로_이어_요청해_삭제와_지연_저장_사이에도_경계와_필터를_유지한다(
        NotificationCategory category) throws Exception {
        NotificationType type = switch (category) {
            case ALL, INTEREST -> NotificationType.LIKE;
            case CHAT -> NotificationType.CHAT_MESSAGE;
            case SYSTEM -> NotificationType.NOTICE;
        };
        LocalDateTime older = CREATED_AT.minusNanos(1_000);
        Long newerWithLowerId = notification(userId, type, CREATED_AT.plusNanos(1_000), false, false);
        Long sameTimeOlder = notification(userId, type, CREATED_AT, false, true);
        Long oldest = notification(userId, type, older, false, false);
        notification(otherUserId, type, CREATED_AT, false, false);
        notification(otherUserId, type, older, false, false);
        notification(userId, type, CREATED_AT, true, false);
        notification(userId, type, older, true, false);
        if (category != NotificationCategory.ALL) {
            NotificationType otherType = category == NotificationCategory.CHAT
                ? NotificationType.LIKE
                : NotificationType.CHAT_MESSAGE;
            notification(userId, otherType, CREATED_AT, false, false);
            notification(userId, otherType, older, false, false);
        }
        Long boundary = notification(userId, type, CREATED_AT, false, false);
        List<Map<String, Object>> beforeFirst = snapshot();

        Page first = page(category, null, 2);

        assertThat(first.ids()).containsExactly(newerWithLowerId, boundary);
        assertThat(first.hasNext()).isTrue();
        assertThat(cursorCodec.decode(first.nextCursor())).isEqualTo(new NotificationCursor(CREATED_AT, boundary));
        assertThat(first.items().get(0).path("createdAt").asString()).isEqualTo("2026-10-24T12:00:00.123457");
        assertThat(snapshot()).isEqualTo(beforeFirst);
        HttpResponse<String> deletion = send(request("/api/v1/notifications/" + boundary, true).DELETE().build());
        assertThat(deletion.statusCode()).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT deleted_at FROM notifications WHERE id = ?",
            LocalDateTime.class, boundary)).isNotNull();
        Long delayedOlder = notification(userId, type, older, false, false);
        Long delayedNewer = notification(userId, type, CREATED_AT.plusNanos(2_000), false, false);
        assertThat(delayedOlder).isGreaterThan(boundary);
        List<Map<String, Object>> afterChanges = snapshot();

        Page second = page(category, first.nextCursor(), 2);
        Page third = page(category, second.nextCursor(), 2);
        Page refreshed = page(category, null, 2);

        assertThat(second.ids()).containsExactly(sameTimeOlder, delayedOlder);
        assertThat(second.hasNext()).isTrue();
        assertThat(cursorCodec.decode(second.nextCursor()))
            .isEqualTo(new NotificationCursor(older, delayedOlder));
        assertThat(third.ids()).containsExactly(oldest);
        assertThat(third.hasNext()).isFalse();
        assertThat(third.nextCursor()).isNull();
        List<Long> received = new ArrayList<>(first.ids());
        received.addAll(second.ids());
        received.addAll(third.ids());
        assertThat(received).containsExactly(newerWithLowerId, boundary, sameTimeOlder, delayedOlder, oldest);
        assertThat(refreshed.ids()).containsExactly(delayedNewer, newerWithLowerId);
        assertThat(snapshot()).isEqualTo(afterChanges);
    }

    @Test
    void 기본_20건과_추가_한_건을_HTTP_커서로_끝까지_조회한다() throws Exception {
        List<Long> ids = new ArrayList<>();
        for (int index = 0; index < 21; index++) {
            ids.add(notification(userId, NotificationType.NOTICE, CREATED_AT, false, false));
        }
        List<Map<String, Object>> before = snapshot();

        Page first = page("");
        Page second = page("?cursor=" + first.nextCursor());

        assertThat(first.ids()).containsExactlyElementsOf(ids.subList(1, 21).reversed());
        assertThat(first.hasNext()).isTrue();
        assertThat(second.ids()).containsExactly(ids.getFirst());
        assertThat(second.hasNext()).isFalse();
        assertThat(second.nextCursor()).isNull();
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void 잘못된_커서는_HTTP_400과_오류_코드만_반환하고_저장_상태를_보존한다() throws Exception {
        notification(userId, NotificationType.LIKE, CREATED_AT, false, true);
        List<Map<String, Object>> before = snapshot();

        HttpResponse<String> response = send(request("/api/v1/notifications?cursor=100", true).GET().build());

        assertError(response, 400, "INVALID_NOTIFICATION_CURSOR");
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void 실제_회원_상태와_인증_필터를_거쳐_401과_403을_구분한다() throws Exception {
        assertError(send(request("/api/v1/notifications", false).GET().build()), 401, "AUTH_REQUIRED");
        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE id = ?", userId);
        assertError(send(request("/api/v1/notifications", true).GET().build()), 403, "REQUESTER_NOT_ACTIVE");
        jdbc.update("UPDATE users SET status = 'WITHDRAWN' WHERE id = ?", userId);
        assertError(send(request("/api/v1/notifications", true).GET().build()), 401, "AUTH_REQUIRED");
    }

    private Long activeUser(String name) {
        return transaction.execute(status -> {
            User user = User.create(name, LocalDate.of(2000, 1, 1), Gender.FEMALE, CREATED_AT);
            user.activate(CREATED_AT);
            return userRepository.saveAndFlush(user).getId();
        });
    }

    private Long notification(Long recipientId, NotificationType type, LocalDateTime createdAt,
        boolean deleted, boolean read) {
        return transaction.execute(status -> {
            Notification notification = Notification.fromSourceEvent("LIST_TEST:" + sourceSequence++,
                userRepository.getReferenceById(recipientId), type, "저장된 제목", "저장된 본문",
                null, null, createdAt);
            if (read) {
                notification.markAsRead(CREATED_AT.plusMinutes(1));
            }
            if (deleted) {
                notification.markAsDeleted(CREATED_AT.plusMinutes(2));
            }
            return notificationRepository.saveAndFlush(notification).getId();
        });
    }

    private Page page(NotificationCategory category, String cursor, int size) throws Exception {
        String query = "?category=" + category.name() + "&size=" + size;
        return page(cursor == null ? query : query + "&cursor=" + cursor);
    }

    private Page page(String query) throws Exception {
        HttpResponse<String> response = send(request("/api/v1/notifications" + query, true).GET().build());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/json");
        JsonNode root = objectMapper.readTree(response.body());
        assertThat(root.path("message").asString()).isEqualTo("notification_list_success");
        JsonNode items = root.path("data").path("items");
        assertThat(items.isArray()).isTrue();
        List<Long> ids = new ArrayList<>();
        items.forEach(item -> ids.add(item.path("notificationId").asLong()));
        JsonNode pageInfo = root.path("data").path("pageInfo");
        assertThat(pageInfo.path("hasNext").isBoolean()).isTrue();
        assertThat(pageInfo.has("nextCursor")).isTrue();
        boolean hasNext = pageInfo.path("hasNext").asBoolean();
        JsonNode token = pageInfo.path("nextCursor");
        String nextCursor = null;
        if (hasNext) {
            assertThat(token.isString()).isTrue();
            nextCursor = token.asString();
            assertThat(nextCursor).isNotBlank();
        } else {
            assertThat(token.isNull()).isTrue();
        }
        return new Page(ids, nextCursor, hasNext, items);
    }

    private HttpRequest.Builder request(String path, boolean authenticated) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(5)).header("Accept", "application/json");
        return authenticated ? builder.header("Authorization", "Bearer list-token") : builder;
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private List<Map<String, Object>> snapshot() {
        return jdbc.queryForList("SELECT * FROM notifications WHERE user_id IN (?, ?) ORDER BY id", userId,
            otherUserId);
    }

    private void assertError(HttpResponse<String> response, int status, String errorCode) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.body()).isEqualTo("{\"errorCode\":\"" + errorCode + "\"}");
    }

    private record Page(List<Long> ids, String nextCursor, boolean hasNext, JsonNode items) {}

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = KafkaAutoConfiguration.class)
    @EntityScan(basePackageClasses = {Notification.class, User.class})
    @EnableJpaRepositories(basePackageClasses = {NotificationRepository.class, UserRepository.class})
    @EnableConfigurationProperties(SecurityProperties.class)
    @Import(
        {
            SecurityConfig.class, ApiAuthenticationEntryPoint.class, ApiAccessDeniedHandler.class,
            UserAuthenticationService.class, GlobalExceptionHandler.class, NotificationController.class,
            NotificationAccessService.class, NotificationListService.class, NotificationDeleteService.class,
            NotificationReadService.class, NotificationUnreadCountService.class, NotificationCursorCodec.class
        }
    )
    static class TestApplication {}
}

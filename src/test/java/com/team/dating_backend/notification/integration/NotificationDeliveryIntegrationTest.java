package com.team.dating_backend.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.team.dating_backend.auth.service.JwtService;
import com.team.dating_backend.common.exception.GlobalExceptionHandler;
import com.team.dating_backend.notification.config.NotificationSseProperties;
import com.team.dating_backend.notification.config.NotificationKafkaProperties;
import com.team.dating_backend.notification.config.NotificationDeliveryProperties;
import com.team.dating_backend.notification.sse.NotificationDeliveryReadinessTracker;
import com.team.dating_backend.notification.sse.NotificationSseLifecycle;
import com.team.dating_backend.notification.controller.NotificationStreamController;
import com.team.dating_backend.notification.dto.response.NotificationEventResponse;
import com.team.dating_backend.notification.entity.Notification;
import com.team.dating_backend.notification.enums.NotificationType;
import com.team.dating_backend.notification.event.NotificationCreatedEventListener;
import com.team.dating_backend.notification.exception.NotificationStreamExceptionHandler;
import com.team.dating_backend.notification.repository.NotificationRepository;
import com.team.dating_backend.notification.service.NotificationAccessService;
import com.team.dating_backend.notification.service.NotificationCreateService;
import com.team.dating_backend.notification.service.NotificationSubscriptionService;
import com.team.dating_backend.notification.sse.NotificationEmitterRegistry;
import com.team.dating_backend.notification.sse.NotificationSseConnection;
import com.team.dating_backend.notification.sse.NotificationSseSender;
import com.team.dating_backend.security.ApiAccessDeniedHandler;
import com.team.dating_backend.security.ApiAuthenticationEntryPoint;
import com.team.dating_backend.security.UserAuthenticationService;
import com.team.dating_backend.security.config.SecurityConfig;
import com.team.dating_backend.security.config.SecurityProperties;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.Gender;
import com.team.dating_backend.user.enums.PersonaOnboardingStatus;
import com.team.dating_backend.user.repository.UserRepository;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
    classes = NotificationDeliveryIntegrationTest.TestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.config.name=notification-delivery-test",
        "app.notification.kafka.create-requested-topic=test-create",
        "app.notification.kafka.delivery-topic=test-delivery",
        "server.address=127.0.0.1",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "app.notification.sse.timeout-ms=30000"
    }
)
@Testcontainers
class NotificationDeliveryIntegrationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 18, 12, 0);

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @LocalServerPort
    private int port;

    @Autowired
    private NotificationCreateService service;

    @Autowired
    private NotificationRepository repository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoSpyBean
    private NotificationEmitterRegistry registry;

    @MockitoSpyBean
    private NotificationSseSender sender;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private UserAuthenticationService userAuthenticationService;

    private TransactionTemplate transaction;
    private HttpClient client;
    private ExecutorService readerExecutor;
    private Long recipientId;
    private Long otherUserId;

    @BeforeEach
    void setUp() {
        transaction = new TransactionTemplate(transactionManager);
        recipientId = createUser("수신자");
        otherUserId = createUser("다른회원");
        given(jwtService.parseServiceAuthToken("recipient-token")).willReturn(recipientId);
        given(jwtService.parseServiceAuthToken("other-token")).willReturn(otherUserId);
        given(userAuthenticationService.isAuthenticationAllowed(anyLong())).willReturn(true);
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        readerExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        for (NotificationSseConnection connection : registry.snapshotAll()) {
            registry.remove(connection);
            connection.getEmitter().complete();
        }
        client.shutdownNow();
        readerExecutor.shutdownNow();
        assertThat(readerExecutor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        transaction.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM notifications WHERE user_id IN (?, ?)", recipientId, otherUserId);
            jdbc.update("DELETE FROM users WHERE id IN (?, ?)", recipientId, otherUserId);
        });
    }

    @Test
    void 커밋_이후_수신자의_모든_SSE_연결에_저장된_알림을_전달한다() throws Exception {
        StreamProbe first = subscribe("recipient-token");
        StreamProbe second = subscribe("recipient-token");
        StreamProbe other = subscribe("other-token");

        transaction.executeWithoutResult(status -> {
            createNotification();
            verify(sender, never()).sendNotification(anyLong(), any());
        });

        NotificationEventResponse stored = storedNotification();
        assertThat(readNotification(first)).isEqualTo(stored);
        assertThat(readNotification(second)).isEqualTo(stored);
        assertThat(other.frames().poll(200, TimeUnit.MILLISECONDS)).isNull();
        verify(sender).sendNotification(recipientId, stored);
        verify(sender, never()).sendNotification(eq(otherUserId), any());
        assertThat(repository.countVisibleUnreadByUserId(recipientId)).isEqualTo(1);
    }

    @Test
    void 업무_트랜잭션이_롤백되면_알림을_저장하거나_SSE로_전달하지_않는다() throws Exception {
        StreamProbe stream = subscribe("recipient-token");
        IllegalStateException failure = new IllegalStateException("업무 처리 실패");

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            userRepository.findById(recipientId).orElseThrow().confirmPersonaOnboarding(NOW);
            createNotification();
            throw failure;
        })).isSameAs(failure);

        verify(sender, never()).sendNotification(anyLong(), any());
        assertThat(stream.frames().poll(200, TimeUnit.MILLISECONDS)).isNull();
        assertThat(repository.countVisibleUnreadByUserId(recipientId)).isZero();
        assertThat(userRepository.findById(recipientId).orElseThrow().getPersonaOnboardingStatus())
            .isEqualTo(PersonaOnboardingStatus.PENDING);
    }

    @Test
    void SSE_연결이_없어도_알림을_저장하고_미읽음_상태를_유지한다() {
        transaction.executeWithoutResult(status -> createNotification());

        NotificationEventResponse stored = storedNotification();
        verify(sender).sendNotification(recipientId, stored);
        assertThat(registry.snapshotAll()).isEmpty();
        assertThat(stored.readAt()).isNull();
        assertThat(repository.countVisibleUnreadByUserId(recipientId)).isEqualTo(1);
    }

    @Test
    void 한_연결의_전송_실패에도_다음_연결로_전달하고_커밋된_알림을_유지한다() throws Exception {
        StreamProbe stream = subscribe("recipient-token");
        NotificationSseConnection healthy = registry.findByUserId(recipientId).getFirst();
        SseEmitter failedEmitter = mock(SseEmitter.class);
        doThrow(new IOException("연결 종료"))
            .when(failedEmitter).send(any(SseEmitter.SseEventBuilder.class));
        NotificationSseConnection failed = new NotificationSseConnection(
            recipientId, "failed-connection", failedEmitter, NOW);
        registry.register(failed);
        doReturn(List.of(failed, healthy)).when(registry).findByUserId(recipientId);

        transaction.executeWithoutResult(status -> createNotification());

        NotificationEventResponse stored = storedNotification();
        assertThat(readNotification(stream)).isEqualTo(stored);
        verify(failedEmitter).send(any(SseEmitter.SseEventBuilder.class));
        assertThat(failed.isClosed()).isTrue();
        assertThat(healthy.isClosed()).isFalse();
        assertThat(registry.snapshotAll()).containsExactly(healthy);
        assertThat(repository.countVisibleUnreadByUserId(recipientId)).isEqualTo(1);
    }

    @Test
    void 예상하지_못한_전송_오류에도_커밋된_업무와_알림을_유지한다() {
        doThrow(new IllegalStateException("전송 처리 실패"))
            .when(sender).sendNotification(anyLong(), any());

        transaction.executeWithoutResult(status -> {
            userRepository.findById(recipientId).orElseThrow().confirmPersonaOnboarding(NOW);
            createNotification();
        });

        verify(sender).sendNotification(recipientId, storedNotification());
        assertThat(repository.countVisibleUnreadByUserId(recipientId)).isEqualTo(1);
        assertThat(userRepository.findById(recipientId).orElseThrow().getPersonaOnboardingStatus())
            .isEqualTo(PersonaOnboardingStatus.CONFIRMED);
    }

    private Long createUser(String name) {
        return transaction.execute(status -> {
            User user = User.create(name, LocalDate.of(2000, 1, 1), Gender.FEMALE, NOW);
            user.activate(NOW);
            return userRepository.saveAndFlush(user).getId();
        });
    }

    private void createNotification() {
        service.createNotification(recipientId, NotificationType.MATCH,
            "매칭 성사", "서로의 마음이 연결되었어요.", "CHAT_ROOM", 123L);
    }

    private NotificationEventResponse storedNotification() {
        List<Notification> notifications = repository.findVisibleByUserId(
            recipientId, PageRequest.of(0, 20));
        assertThat(notifications).hasSize(1);
        return NotificationEventResponse.from(notifications.getFirst());
    }

    private StreamProbe subscribe(String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
            URI.create("http://127.0.0.1:" + port + "/api/v1/notifications/stream"))
            .header("Authorization", "Bearer " + token)
            .header("Accept", "text/event-stream")
            .GET().build();
        var response = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
            .get(5, TimeUnit.SECONDS);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow())
            .startsWith("text/event-stream");

        BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        readerExecutor.submit(() -> {
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String frame;
                while ((frame = readFrame(reader)) != null) {
                    frames.add(frame);
                }
            }
            return null;
        });
        assertThat(frames.poll(5, TimeUnit.SECONDS)).startsWith("event:connected\n");
        return new StreamProbe(frames);
    }

    private NotificationEventResponse readNotification(StreamProbe stream) throws Exception {
        String frame = stream.frames().poll(5, TimeUnit.SECONDS);
        assertThat(frame).startsWith("event:notification\n");
        String data = frame.lines().filter(line -> line.startsWith("data:"))
            .map(line -> line.substring("data:".length())).findFirst().orElseThrow();
        return objectMapper.readValue(data, NotificationEventResponse.class);
    }

    private String readFrame(BufferedReader reader) throws IOException {
        StringBuilder frame = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            frame.append(line).append('\n');
            if (line.isEmpty()) {
                return frame.toString();
            }
        }
        return null;
    }

    private record StreamProbe(BlockingQueue<String> frames) {}

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EntityScan("com.team.dating_backend")
    @EnableJpaRepositories(basePackageClasses = {NotificationRepository.class, UserRepository.class})
    @EnableConfigurationProperties(
        {NotificationSseProperties.class, SecurityProperties.class, NotificationKafkaProperties.class,
            NotificationDeliveryProperties.class}
    )
    @Import(
        {
            SecurityConfig.class,
            ApiAuthenticationEntryPoint.class,
            ApiAccessDeniedHandler.class,
            GlobalExceptionHandler.class,
            NotificationStreamController.class,
            NotificationStreamExceptionHandler.class,
            NotificationAccessService.class,
            NotificationCreateService.class,
            NotificationSubscriptionService.class, NotificationSseLifecycle.class,
            NotificationDeliveryReadinessTracker.class,
            NotificationCreatedEventListener.class,
            NotificationEmitterRegistry.class,
            NotificationSseSender.class
        }
    )
    static class TestApplication {}
}

package com.team.dating_backend.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;

import com.team.dating_backend.auth.service.JwtService;
import com.team.dating_backend.common.config.SchedulingConfig;
import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.common.exception.GlobalExceptionHandler;
import com.team.dating_backend.notification.config.NotificationSseProperties;
import com.team.dating_backend.notification.config.NotificationKafkaProperties;
import com.team.dating_backend.notification.config.NotificationDeliveryProperties;
import com.team.dating_backend.notification.sse.NotificationDeliveryReadinessTracker;
import com.team.dating_backend.notification.sse.NotificationSseLifecycle;
import com.team.dating_backend.notification.controller.NotificationStreamController;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import com.team.dating_backend.notification.exception.NotificationStreamExceptionHandler;
import com.team.dating_backend.notification.service.NotificationAccessService;
import com.team.dating_backend.notification.service.NotificationSubscriptionService;
import com.team.dating_backend.notification.sse.NotificationEmitterRegistry;
import com.team.dating_backend.notification.sse.NotificationHeartbeatScheduler;
import com.team.dating_backend.notification.sse.NotificationSseConnection;
import com.team.dating_backend.notification.sse.NotificationSseSender;
import com.team.dating_backend.security.ApiAccessDeniedHandler;
import com.team.dating_backend.security.ApiAuthenticationEntryPoint;
import com.team.dating_backend.security.UserAuthenticationService;
import com.team.dating_backend.security.config.SecurityConfig;
import com.team.dating_backend.security.config.SecurityProperties;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@SpringBootTest(
    classes = NotificationStreamIntegrationTest.TestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.config.name=notification-stream-test",
        "app.notification.kafka.create-requested-topic=test-create",
        "app.notification.kafka.delivery-topic=test-delivery",
        "server.address=127.0.0.1",
        "app.notification.sse.heartbeat-delay-ms=50"
    }
)
class NotificationStreamIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private NotificationSseProperties properties;

    @Autowired
    private NotificationEmitterRegistry registry;

    @MockitoSpyBean
    private NotificationSseSender sender;

    @MockitoBean
    private NotificationAccessService accessService;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private UserAuthenticationService userAuthenticationService;

    private HttpClient client;
    private ExecutorService readerExecutor;

    @BeforeEach
    void setUp() {
        properties.setTimeoutMs(5_000);
        given(jwtService.parseServiceAuthToken("valid-token")).willReturn(42L);
        given(userAuthenticationService.isAuthenticationAllowed(42L)).willReturn(true);
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        readerExecutor = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        client.shutdownNow();
        for (NotificationSseConnection connection : registry.snapshotAll()) {
            registry.remove(connection);
            connection.getEmitter().complete();
        }
        readerExecutor.shutdownNow();
        assertThat(readerExecutor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void heartbeat가_계속_전송되어도_타임아웃으로_HTTP_응답이_끝나고_연결이_제거된다() throws Exception {
        properties.setTimeoutMs(200);

        HttpResponse<String> response = client.sendAsync(request(), HttpResponse.BodyHandlers.ofString())
            .get(8, TimeUnit.SECONDS);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("text/event-stream");
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).isEqualTo("no-cache, no-store");
        assertThat(response.body()).startsWith("event:connected\n")
            .contains(":heartbeat\n\n").doesNotContain("errorCode");
        assertThat(registry.snapshotAll()).isEmpty();
        verify(accessService).assertActiveUser(42L);
        verify(jwtService).parseServiceAuthToken("valid-token");
    }

    @Test
    void 미확정_초기_직렬화_실패는_500_JSON으로_끝나고_연결을_남기지_않는다() throws Exception {
        doNothing().when(sender).sendHeartbeat(any());
        doAnswer(invocation -> {
            NotificationSseConnection connection = invocation.getArgument(0);
            connection.getEmitter().send(SseEmitter.event()
                .name("connected")
                .data(new BrokenPayload("value"), MediaType.APPLICATION_JSON));
            return null;
        }).when(sender).sendConnected(any());

        HttpResponse<String> response = client.sendAsync(request(), HttpResponse.BodyHandlers.ofString())
            .get(8, TimeUnit.SECONDS);

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/json");
        assertThat(response.body()).isEqualTo("{\"errorCode\":\"INTERNAL_SERVER_ERROR\"}");
        assertThat(registry.snapshotAll()).isEmpty();
    }

    @Test
    void 응답_확정_후_오류는_JSON을_추가하지_않고_HTTP_스트림을_종료한다() throws Exception {
        HttpResponse<InputStream> response = client.sendAsync(request(), HttpResponse.BodyHandlers.ofInputStream())
            .get(5, TimeUnit.SECONDS);

        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String first = readerExecutor.submit(() -> readFrame(reader)).get(5, TimeUnit.SECONDS);
            assertThat(first).startsWith("event:connected\n");
            NotificationSseConnection connection = registry.findByUserId(42L).getFirst();

            connection.getEmitter().completeWithError(
                new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR));

            String remaining = readerExecutor.submit(() -> reader.lines().collect(Collectors.joining("\n")))
                .get(5, TimeUnit.SECONDS);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(remaining).doesNotContain("errorCode", "INTERNAL_SERVER_ERROR", "AUTH_REQUIRED");
            assertThat(registry.snapshotAll()).isEmpty();
            verify(accessService).assertActiveUser(42L);
        }
    }

    private HttpRequest request() {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/notifications/stream"))
            .timeout(Duration.ofSeconds(6))
            .header("Authorization", "Bearer valid-token")
            .header("Accept", "text/event-stream, application/json")
            .GET().build();
    }

    private String readFrame(BufferedReader reader) throws Exception {
        StringBuilder frame = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            frame.append(line).append('\n');
            if (line.isEmpty()) {
                break;
            }
        }
        return frame.toString();
    }

    record BrokenPayload(String value) {
        @Override
        public String value() {
            throw new IllegalStateException();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class})
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
            NotificationSubscriptionService.class, NotificationSseLifecycle.class,
            NotificationDeliveryReadinessTracker.class,
            NotificationEmitterRegistry.class,
            NotificationSseSender.class,
            NotificationStreamExceptionHandler.class,
            NotificationHeartbeatScheduler.class,
            SchedulingConfig.class
        }
    )
    static class TestApplication {}
}

package com.team.dating_backend.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;

import com.team.dating_backend.auth.service.JwtService;
import com.team.dating_backend.notification.config.NotificationCreationProperties;
import com.team.dating_backend.notification.config.NotificationKafkaConfiguration;
import com.team.dating_backend.notification.dto.event.NotificationDeliveryEvent;
import com.team.dating_backend.notification.dto.response.NotificationEventResponse;
import com.team.dating_backend.notification.enums.NotificationErrorCode;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import com.team.dating_backend.notification.kafka.NotificationCreationFailureClassifier;
import com.team.dating_backend.notification.kafka.NotificationDeliveryConsumer;
import com.team.dating_backend.notification.kafka.NotificationDeliveryReadinessListener;
import com.team.dating_backend.notification.kafka.NotificationRecoveryTestConfiguration;
import com.team.dating_backend.notification.service.NotificationAccessService;
import com.team.dating_backend.notification.service.NotificationDeliveryEventValidator;
import com.team.dating_backend.notification.sse.NotificationDeliveryReadinessTracker;
import com.team.dating_backend.notification.sse.NotificationEmitterRegistry;
import com.team.dating_backend.notification.sse.NotificationSseLifecycle;
import com.team.dating_backend.notification.sse.NotificationSseMetrics;
import com.team.dating_backend.security.UserAuthenticationService;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
    classes = NotificationSubscriptionReadinessIntegrationTest.TestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.config.name=notification-readiness-test",
        "server.address=127.0.0.1",
        "spring.kafka.admin.auto-create=false",
        "spring.kafka.consumer.properties.allow.auto.create.topics=false",
        "app.notification.delivery.enabled=true",
        "app.notification.delivery.concurrency=2",
        "app.notification.sse.timeout-ms=20000",
        "app.notification.sse.heartbeat-delay-ms=50"
    }
)
class NotificationSubscriptionReadinessIntegrationTest {

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1")
        .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");
    private static final String TOPIC = "subscription-readiness-" + UUID.randomUUID();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("app.notification.kafka.delivery-topic", () -> TOPIC);
        registry.add("app.notification.kafka.create-requested-topic", () -> TOPIC + "-create");
    }

    @LocalServerPort
    private int port;
    @Autowired
    private KafkaListenerEndpointRegistry endpoints;
    @Autowired
    private NotificationEmitterRegistry connections;
    @Autowired
    private NotificationDeliveryReadinessTracker tracker;
    @Autowired
    private NotificationSseLifecycle lifecycle;
    @Autowired
    private MeterRegistry meters;
    @Autowired
    private KafkaTemplate<Object, Object> template;
    @Autowired
    private JsonMapper json;
    @MockitoSpyBean
    private NotificationDeliveryReadinessListener readinessListener;
    @MockitoBean
    private NotificationAccessService access;
    @MockitoBean
    private JwtService jwt;
    @MockitoBean
    private UserAuthenticationService users;

    @Test
    void 전체_준비_후_구독하고_소비_중단에는_기존_HTTP_SSE와_heartbeat를_유지하며_재시작_후_재개한다() throws Exception {
        given(jwt.parseServiceAuthToken("valid-token")).willReturn(42L);
        given(users.isAuthenticationAllowed(42L)).willReturn(true);
        MessageListenerContainer container = endpoints.getListenerContainer("notificationDeliveryConsumer");
        assertThat(container).isNotNull();
        assertThat(container.isRunning()).isFalse();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var firstConfirmed = new AtomicBoolean();
        var gate = new AtomicBoolean(true);
        doAnswer(invocation -> {
            Collection<TopicPartition> assigned = invocation.getArgument(1);
            if (assigned.size() == 1 && assigned.contains(new TopicPartition(TOPIC, 1))
                && gate.compareAndSet(true, false)) {
                entered.countDown();
                assertThat(release.await(20, TimeUnit.SECONDS)).isTrue();
            }
            Object result = invocation.callRealMethod();
            if (assigned.size() == 1 && assigned.contains(new TopicPartition(TOPIC, 0))) {
                firstConfirmed.set(true);
            }
            return result;
        }).when(readinessListener).onPartitionsAssigned(any(), any());
        List<Probe> probes = new ArrayList<>();
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
            var readers = Executors.newCachedThreadPool();
            var admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            try {
                assertError(client, false, 401, "AUTH_REQUIRED");
                doThrow(new NotificationBusinessException(NotificationErrorCode.REQUESTER_NOT_ACTIVE))
                    .when(access).assertActiveUser(42L);
                assertError(client, true, 403, "REQUESTER_NOT_ACTIVE");
                doNothing().when(access).assertActiveUser(42L);
                assertError(client, true, 503, "NOTIFICATION_STREAM_NOT_READY");
                assertThat(connections.snapshotAll()).isEmpty();
                admin.createTopics(List.of(new NewTopic(TOPIC, 2, (short) 1))).all().get(10, TimeUnit.SECONDS);
                container.start();
                assertThat(entered.await(15, TimeUnit.SECONDS)).isTrue();
                await().atMost(Duration.ofSeconds(10)).untilTrue(firstConfirmed);
                assertThat(tracker.isReady()).isFalse();
                assertError(client, true, 503, "NOTIFICATION_STREAM_NOT_READY");
                release.countDown();
                await().atMost(Duration.ofSeconds(15)).until(lifecycle::isAcceptingSubscriptions);
                Probe first = open(client, readers, probes);
                Probe second = open(client, readers, probes);
                send(10);
                await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
                    assertThat(first.notifications()).containsExactly(event(10).notification());
                    assertThat(second.notifications()).containsExactly(event(10).notification());
                    assertThat(meters.get("notification.delivery.ready").gauge().value()).isEqualTo(1);
                    assertThat(meters.get("notification.sse.accepting").gauge().value()).isEqualTo(1);
                    assertThat(meters.get("notification.sse.connections").gauge().value()).isEqualTo(2);
                });
                container.stop();
                assertThat(tracker.isReady()).isFalse();
                assertError(client, true, 503, "NOTIFICATION_STREAM_NOT_READY");
                int heartbeats = first.heartbeats().get();
                await().atMost(Duration.ofSeconds(3)).until(() -> first.heartbeats().get() > heartbeats);
                assertThat(connections.snapshotAll()).hasSize(2)
                    .allSatisfy(connection -> assertThat(connection.isClosed()).isFalse());
                assertThat(meters.get("notification.sse.accepting").gauge().value()).isZero();
                assertThat(meters.get("notification.sse.connections").gauge().value()).isEqualTo(2);
                send(11);
                container.start();
                await().atMost(Duration.ofSeconds(15)).until(lifecycle::isAcceptingSubscriptions);
                await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
                    assertThat(first.notifications()).containsExactly(event(10).notification(),
                        event(11).notification());
                    assertThat(second.notifications()).containsExactlyElementsOf(first.notifications());
                });
                Probe third = open(client, readers, probes);
                send(12);
                await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
                    assertThat(first.notifications()).containsExactly(event(10).notification(),
                        event(11).notification(), event(12).notification());
                    assertThat(second.notifications()).containsExactlyElementsOf(first.notifications());
                    assertThat(third.notifications()).containsExactly(event(12).notification());
                });
                lifecycle.beginDrain();
                assertError(client, true, 503, "NOTIFICATION_STREAM_NOT_READY");
                assertThat(container.isRunning()).isTrue();
                assertThat(tracker.isReady()).isTrue();
                assertThat(connections.snapshotAll()).hasSize(3);
            } finally {
                release.countDown();
                for (var connection : connections.snapshotAll()) {
                    connections.remove(connection);
                    connection.getEmitter().complete();
                }
                for (Probe probe : probes) {
                    probe.body().close();
                }
                container.stop();
                client.shutdownNow();
                readers.shutdownNow();
            }
        }
    }

    private Probe open(HttpClient client, java.util.concurrent.ExecutorService readers, List<Probe> probes)
        throws Exception {
        var response = client.sendAsync(request(true), HttpResponse.BodyHandlers.ofInputStream()).get(8,
            TimeUnit.SECONDS);
        var probe = new Probe(response.body(), new ConcurrentLinkedQueue<>(), new AtomicInteger());
        probes.add(probe);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("text/event-stream");
        var connected = new CountDownLatch(1);
        readers.submit(() -> {
            try (var reader = new BufferedReader(new InputStreamReader(probe.body(), StandardCharsets.UTF_8))) {
                StringBuilder frame = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    frame.append(line).append('\n');
                    if (line.isEmpty()) {
                        String value = frame.toString();
                        if (value.startsWith("event:connected\n")) {
                            connected.countDown();
                        }
                        if (value.contains(":heartbeat")) {
                            probe.heartbeats().incrementAndGet();
                        }
                        if (value.startsWith("event:notification\n")) {
                            String data = value.lines().filter(part -> part.startsWith("data:")).findFirst()
                                .orElseThrow().substring(5);
                            probe.notifications().add(json.readValue(data, NotificationEventResponse.class));
                        }
                        frame.setLength(0);
                    }
                }
            } catch (IOException exception) {
                if (!Thread.currentThread().isInterrupted()) {
                    throw new java.io.UncheckedIOException(exception);
                }
            }
        });
        assertThat(connected.await(5, TimeUnit.SECONDS)).isTrue();
        return probe;
    }

    private void assertError(HttpClient client, boolean authenticated, int status, String errorCode) throws Exception {
        var response = client.sendAsync(request(authenticated), HttpResponse.BodyHandlers.ofString()).get(8,
            TimeUnit.SECONDS);
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/json");
        assertThat(response.body()).isEqualTo("{\"errorCode\":\"" + errorCode + "\"}");
    }

    private HttpRequest request(boolean authenticated) {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/notifications/stream"))
            .timeout(Duration.ofSeconds(10)).header("Accept", "text/event-stream").GET();
        if (authenticated) {
            builder.header("Authorization", "Bearer valid-token");
        }
        return builder.build();
    }

    private void send(long id) throws Exception {
        template.send(TOPIC, 0, 42L, event(id)).get(10, TimeUnit.SECONDS);
    }

    private NotificationDeliveryEvent event(long id) {
        return new NotificationDeliveryEvent(1, "source-" + id, 42L,
            new NotificationEventResponse(id, "LIKE", "제목", "본문", null, null, null,
                LocalDateTime.of(2026, 10, 23, 12, 0)));
    }

    private record Probe(InputStream body, ConcurrentLinkedQueue<NotificationEventResponse> notifications,
        AtomicInteger heartbeats) {}

    @Configuration(proxyBeanMethods = false)
    @EnableKafka
    @EnableConfigurationProperties(NotificationCreationProperties.class)
    @Import(
        {NotificationStreamIntegrationTest.TestApplication.class, NotificationKafkaConfiguration.class,
            NotificationDeliveryConsumer.class, NotificationDeliveryEventValidator.class,
            NotificationRecoveryTestConfiguration.class, NotificationCreationFailureClassifier.class,
            NotificationSseMetrics.class}
    )
    static class TestApplication {
        @Bean
        static BeanPostProcessor manualDeliveryStart() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (name.equals("notificationDeliveryKafkaListenerContainerFactory")
                        && bean instanceof ConcurrentKafkaListenerContainerFactory<?, ?> factory) {
                        factory.setContainerCustomizer(container -> container.setAutoStartup(false));
                    }
                    return bean;
                }
            };
        }
    }
}

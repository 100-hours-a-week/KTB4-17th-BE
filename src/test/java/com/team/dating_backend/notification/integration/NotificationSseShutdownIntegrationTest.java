package com.team.dating_backend.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.team.dating_backend.auth.service.JwtService;
import com.team.dating_backend.notification.config.NotificationCreationProperties;
import com.team.dating_backend.notification.config.NotificationKafkaConfiguration;
import com.team.dating_backend.notification.dto.event.NotificationDeliveryEvent;
import com.team.dating_backend.notification.dto.response.NotificationEventResponse;
import com.team.dating_backend.notification.kafka.NotificationCreationFailureClassifier;
import com.team.dating_backend.notification.kafka.NotificationDeliveryConsumer;
import com.team.dating_backend.notification.kafka.NotificationRecoveryTestConfiguration;
import com.team.dating_backend.notification.service.NotificationAccessService;
import com.team.dating_backend.notification.service.NotificationDeliveryEventValidator;
import com.team.dating_backend.notification.sse.NotificationEmitterRegistry;
import com.team.dating_backend.notification.sse.NotificationSseConnection;
import com.team.dating_backend.notification.sse.NotificationSseLifecycle;
import com.team.dating_backend.notification.sse.NotificationSseMetrics;
import com.team.dating_backend.notification.sse.NotificationSseProcessIdentity;
import com.team.dating_backend.notification.sse.NotificationSseSender;
import com.team.dating_backend.notification.sse.NotificationSseShutdownCoordinator;
import com.team.dating_backend.security.UserAuthenticationService;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.BufferedReader;
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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.event.ContainerStoppedEvent;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
class NotificationSseShutdownIntegrationTest {

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1")
        .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 실제_Context_종료는_기존_전파를_유지한_뒤_연결과_Consumer를_정리하고_다른_Context를_보존한다(boolean enabled)
        throws Exception {
        String topic = "shutdown-" + UUID.randomUUID();
        try (
            var admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get(15, TimeUnit.SECONDS);
        }
        try (var first = start(topic, enabled);
            var second = enabled ? start(topic, true) : null;
            var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
            var readers = Executors.newVirtualThreadPerTaskExecutor();
            var closing = Executors.newSingleThreadExecutor()) {
            var gate = first.getBean(ShutdownGate.class);
            var registry = first.getBean(NotificationEmitterRegistry.class);
            var meters = first.getBean(MeterRegistry.class);
            var probes = new ArrayList<Probe>();
            try {
                var firstStream = open(first, client, readers, probes);
                var secondStream = open(first, client, readers, probes);
                var otherStream = second == null ? null : open(second, client, readers, probes);
                send(first, topic, enabled, 10);
                assertNotifications(firstStream, 10);
                assertNotifications(secondStream, 10);
                if (second != null) {
                    assertNotifications(otherStream, 10);
                    assertThat(first.getBean(NotificationSseProcessIdentity.class).getGroupId())
                        .isNotEqualTo(second.getBean(NotificationSseProcessIdentity.class).getGroupId());
                }
                gate.connections = registry.snapshotAll();
                gate.container = first.getBean(KafkaListenerEndpointRegistry.class)
                    .getListenerContainer("notificationDeliveryConsumer");
                gate.armed.set(true);
                var shutdown = closing.submit(first::close);
                assertThat(gate.entered.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(shutdown.isDone()).isFalse();
                var rejected = client.sendAsync(request(first), HttpResponse.BodyHandlers.ofString())
                    .get(5, TimeUnit.SECONDS);
                assertThat(rejected.statusCode()).isEqualTo(503);
                assertThat(rejected.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/json");
                assertThat(rejected.body()).isEqualTo("{\"errorCode\":\"NOTIFICATION_STREAM_NOT_READY\"}");
                assertThat(registry.snapshotAll()).hasSize(2);
                assertThat(gate.container.isRunning()).isEqualTo(enabled);
                int heartbeats = firstStream.heartbeats.get();
                send(first, topic, enabled, 11);
                assertNotifications(firstStream, 10, 11);
                assertNotifications(secondStream, 10, 11);
                await().atMost(Duration.ofSeconds(3)).until(() -> firstStream.heartbeats.get() > heartbeats);
                Probe newOtherStream = null;
                if (second != null) {
                    newOtherStream = open(second, client, readers, probes);
                    send(second, topic, true, 12);
                    assertNotifications(firstStream, 10, 11, 12);
                    assertNotifications(secondStream, 10, 11, 12);
                    assertNotifications(otherStream, 10, 11, 12);
                    assertNotifications(newOtherStream, 12);
                }
                gate.release.countDown();
                shutdown.get(20, TimeUnit.SECONDS);
                firstStream.reading.get(5, TimeUnit.SECONDS);
                secondStream.reading.get(5, TimeUnit.SECONDS);
                assertThat(registry.snapshotAll()).isEmpty();
                assertThat(gate.connections).allSatisfy(connection -> assertThat(connection.isClosed()).isTrue());
                assertThat(gate.container.isRunning()).isFalse();
                if (enabled) {
                    assertThat(gate.stopped.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThat(gate.connectionsClosedBeforeStop.get()).isTrue();
                    send(second, topic, true, 13);
                    assertNotifications(otherStream, 10, 11, 12, 13);
                    assertNotifications(newOtherStream, 12, 13);
                    assertThat(second.getBean(NotificationSseLifecycle.class).isAcceptingSubscriptions()).isTrue();
                }
                assertThat(meters.get("notification.sse.connections.closed").tag("reason", "SHUTDOWN")
                    .counter().count()).isEqualTo(2);
            } finally {
                gate.release.countDown();
                for (var probe : probes) {
                    probe.body.close();
                }
                client.shutdownNow();
                readers.shutdownNow();
            }
        }
    }

    private ConfigurableApplicationContext start(String topic, boolean enabled) {
        var context = new SpringApplicationBuilder(TestApplication.class).web(WebApplicationType.SERVLET)
            .run("--spring.config.name=notification-shutdown-test", "--server.port=0", "--server.address=127.0.0.1",
                "--spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers(),
                "--spring.kafka.admin.auto-create=false",
                "--spring.kafka.consumer.properties.allow.auto.create.topics=false",
                "--app.notification.kafka.create-requested-topic=" + topic + "-create",
                "--app.notification.kafka.delivery-topic=" + topic,
                "--app.notification.delivery.enabled=" + enabled,
                "--app.notification.sse.timeout-ms=60000", "--app.notification.sse.heartbeat-delay-ms=50",
                "--app.notification.sse.drain-timeout-ms=100", "--app.notification.sse.close-timeout-ms=1000",
                "--app.notification.sse.consumer-stop-timeout-ms=10000");
        try {
            await().atMost(Duration.ofSeconds(30))
                .until(context.getBean(NotificationSseLifecycle.class)::isAcceptingSubscriptions);
            return context;
        } catch (RuntimeException | Error failure) {
            context.close();
            throw failure;
        }
    }

    private Probe open(ConfigurableApplicationContext context, HttpClient client, ExecutorService readers,
        List<Probe> probes) throws Exception {
        var response = client.sendAsync(request(context), HttpResponse.BodyHandlers.ofInputStream())
            .get(8, TimeUnit.SECONDS);
        assertThat(response.statusCode()).isEqualTo(200);
        var probe = new Probe(response.body());
        probes.add(probe);
        var connected = new CountDownLatch(1);
        var json = context.getBean(JsonMapper.class);
        probe.reading = readers.submit(() -> {
            try (var reader = new BufferedReader(new InputStreamReader(probe.body, StandardCharsets.UTF_8))) {
                var frame = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    frame.append(line).append('\n');
                    if (line.isEmpty()) {
                        String value = frame.toString();
                        if (value.startsWith("event:connected\n")) {
                            connected.countDown();
                        } else if (value.startsWith("event:notification\n")) {
                            String data = value.lines().filter(part -> part.startsWith("data:")).findFirst()
                                .orElseThrow().substring(5);
                            probe.notifications
                                .add(json.readValue(data, NotificationEventResponse.class).notificationId());
                        } else if (value.contains(":heartbeat")) {
                            probe.heartbeats.incrementAndGet();
                        }
                        assertThat(value).doesNotContain("errorCode");
                        frame.setLength(0);
                    }
                }
            }
            return null;
        });
        assertThat(connected.await(5, TimeUnit.SECONDS)).isTrue();
        return probe;
    }

    private HttpRequest request(ConfigurableApplicationContext context) {
        int port = context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/notifications/stream"))
            .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer valid-token")
            .header("Accept", "text/event-stream").GET().build();
    }

    @SuppressWarnings("unchecked")
    private void send(ConfigurableApplicationContext context, String topic, boolean enabled, long id) throws Exception {
        var snapshot = new NotificationEventResponse(id, "LIKE", "제목", "본문", null, null, null,
            LocalDateTime.of(2026, 10, 24, 12, 0));
        if (enabled) {
            context.getBean(KafkaTemplate.class).send(topic, 0, "42",
                new NotificationDeliveryEvent(1, "source-" + id, 42L, snapshot)).get(10, TimeUnit.SECONDS);
        } else {
            context.getBean(NotificationSseSender.class).sendNotification(42L, snapshot);
        }
    }

    private void assertNotifications(Probe probe, long... ids) {
        await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> assertThat(probe.notifications)
            .containsExactlyElementsOf(Arrays.stream(ids).boxed().toList()));
    }

    private static class Probe {
        private final InputStream body;
        private final ConcurrentLinkedQueue<Long> notifications = new ConcurrentLinkedQueue<>();
        private final AtomicInteger heartbeats = new AtomicInteger();
        private Future<?> reading;

        private Probe(InputStream body) {
            this.body = body;
        }
    }

    private static class ShutdownGate {
        private final AtomicBoolean armed = new AtomicBoolean();
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch stopped = new CountDownLatch(1);
        private final AtomicBoolean connectionsClosedBeforeStop = new AtomicBoolean();
        private List<NotificationSseConnection> connections = List.of();
        private MessageListenerContainer container;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableKafka
    @EnableConfigurationProperties(NotificationCreationProperties.class)
    @Import(
        {NotificationStreamIntegrationTest.TestApplication.class, NotificationKafkaConfiguration.class,
            NotificationDeliveryConsumer.class, NotificationDeliveryEventValidator.class,
            NotificationRecoveryTestConfiguration.class, NotificationCreationFailureClassifier.class,
            NotificationSseMetrics.class, NotificationSseShutdownCoordinator.class}
    )
    static class TestApplication {

        @Bean
        static ShutdownGate shutdownGate() {
            return new ShutdownGate();
        }

        @Bean
        static BeanPostProcessor drainGate(ShutdownGate gate) {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (bean instanceof NotificationSseLifecycle lifecycle) {
                        var observed = spy(lifecycle);
                        doAnswer(invocation -> {
                            invocation.callRealMethod();
                            if (gate.armed.compareAndSet(true, false)) {
                                gate.entered.countDown();
                                assertThat(gate.release.await(15, TimeUnit.SECONDS)).isTrue();
                            }
                            return null;
                        }).when(observed).beginDrain();
                        return observed;
                    }
                    return bean;
                }
            };
        }

        @Bean
        ApplicationListener<ContainerStoppedEvent> stopped(ShutdownGate gate, NotificationEmitterRegistry registry) {
            return event -> {
                if (event.getSource() == gate.container) {
                    gate.connectionsClosedBeforeStop.set(registry.snapshotAll().isEmpty()
                        && gate.connections.stream().allMatch(NotificationSseConnection::isClosed));
                    gate.stopped.countDown();
                }
            };
        }

        @Bean
        NotificationAccessService access() {
            return mock(NotificationAccessService.class);
        }

        @Bean
        JwtService jwt() {
            var jwt = mock(JwtService.class);
            when(jwt.parseServiceAuthToken("valid-token")).thenReturn(42L);
            return jwt;
        }

        @Bean
        UserAuthenticationService users() {
            var users = mock(UserAuthenticationService.class);
            when(users.isAuthenticationAllowed(anyLong())).thenReturn(true);
            return users;
        }
    }
}

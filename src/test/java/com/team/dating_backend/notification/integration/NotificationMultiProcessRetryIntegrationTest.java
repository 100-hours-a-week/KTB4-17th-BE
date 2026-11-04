package com.team.dating_backend.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import com.team.dating_backend.notification.enums.NotificationType;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import com.team.dating_backend.notification.support.NotificationIntegrationEnvironment;
import com.team.dating_backend.notification.support.NotificationKafkaFaultProxy.Channel;
import com.team.dating_backend.notification.support.NotificationKafkaProbe.ObservedRecord;
import com.team.dating_backend.notification.support.NotificationKafkaProbe.Position;
import com.team.dating_backend.notification.support.NotificationMultiProcessTestSupport;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.kafka.retrytopic.RetryTopicHeaders;
import org.springframework.kafka.support.KafkaHeaders;

@Tag("notification-multiprocess")
@Timeout(110)
class NotificationMultiProcessRetryIntegrationTest extends NotificationMultiProcessTestSupport {

    private WebhookFailureProxy webhook;

    @Override
    protected NotificationIntegrationEnvironment createEnvironment() {
        webhook = new WebhookFailureProxy();
        return new NotificationIntegrationEnvironment(NotificationIntegrationEnvironment.Mode.RECOVERY, Map.of(
            "DB_CONNECTION_INIT_SQL", "SET SESSION innodb_lock_wait_timeout = 1",
            "ALERT_ENABLED", "true", "ALERT_WEBHOOK_URL", "https://discord.com/api/webhooks/0/notification-integration",
            "ALERT_CONNECT_TIMEOUT_MS", "10000", "ALERT_READ_TIMEOUT_MS", "10000",
            "HTTPS_PROXY_PORT", Integer.toString(webhook.server.getLocalPort())));
    }

    @AfterEach
    void restoreFailureControls() throws Exception {
        webhook.release();
        environment.kafkaFaultProxy().setEnabled(Channel.PRODUCER, true);
    }

    @AfterAll
    void closeWebhookProxy() throws Exception {
        if (webhook != null) {
            webhook.close();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 4})
    void 인계_실패_동안_입력을_보존하고_복구_후_DLT와_Discord_실패를_격리한다(int failedAttempt) throws Exception {
        long userId = activeUser();
        var request = notification(userId);
        var stream = subscribe(second, userId);
        var kafka = environment.kafkaProbe();
        var database = environment.databaseProbe();
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("failedAttempt", failedAttempt);
        evidence.put("request", request);
        evidence.put("completed", false);
        long alertFailuresBefore = alertFailures();
        evidence.put("alertFailuresBefore", alertFailuresBefore);
        Throwable failure = null;
        webhook.block();
        try {
            var seed = kafka.publish(request, 0);
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                assertThat(kafka.inputState(environment.creationGroup(), seed).completed()).isTrue();
                stream.assertHealthy();
                assertThat(stream.notifications()).hasSize(1);
            });
            var stored = database.notifications(request).getFirst();
            assertThat(request(second, "PUT", NOTIFICATIONS + "/" + stored.id() + "/read-receipt", token(userId), null)
                .statusCode()).isEqualTo(204);
            var expected = database.notifications(request).getFirst();
            try (var lock = database.lockExistingNotification(request)) {
                if (failedAttempt == 1) {
                    environment.kafkaFaultProxy().setEnabled(Channel.PRODUCER, false);
                }
                var original = kafka.publish(request, 0);
                Position input = original;
                String inputGroup = environment.creationGroup();
                if (failedAttempt > 1) {
                    var retryInput = await().atMost(Duration.ofSeconds(35)).until(
                        () -> records(environment.retryTopic(), request).stream()
                            .filter(record -> attempt(record) == failedAttempt).findFirst(),
                        value -> value.isPresent())
                        .orElseThrow();
                    input = retryInput.position();
                    inputGroup += "-retry";
                    environment.kafkaFaultProxy().setEnabled(Channel.PRODUCER, false);
                }
                String code = failedAttempt == 4 ? "DLT_PUBLISH_FAILED" : "RETRY_PUBLISH_FAILED";
                await().atMost(Duration.ofSeconds(25)).until(() -> logged(code, request));
                var blocked = kafka.inputState(inputGroup, input);
                assertThat(blocked.completed()).isFalse();
                assertThat(records(environment.dltTopic(), request)).isEmpty();
                assertThat(database.notifications(request)).containsExactly(expected);
                evidence.put("blocked", Map.of("input", blocked,
                    "retrySnapshot", kafka.snapshot(environment.retryTopic()), "dltSnapshot",
                    kafka.snapshot(environment.dltTopic())));
                environment.kafkaFaultProxy().setEnabled(Channel.PRODUCER, true);
                Position handoffInput = input;
                String handoffGroup = inputGroup;
                await().atMost(Duration.ofSeconds(20))
                    .untilAsserted(() -> assertThat(kafka.inputState(handoffGroup, handoffInput).completed()).isTrue());

                var duringRetry = notification(userId);
                var healthyDuringRetry = kafka.publish(duringRetry, 0);
                await().atMost(Duration.ofSeconds(15)).untilAsserted(
                    () -> assertThat(kafka.inputState(environment.creationGroup(), healthyDuringRetry).completed())
                        .isTrue());
                var dlt = await().atMost(Duration.ofSeconds(35)).until(() -> records(environment.dltTopic(), request),
                    records -> !records.isEmpty()).getFirst();
                await().atMost(Duration.ofSeconds(5)).failFast(() -> webhook.failure.get() != null)
                    .until(() -> webhook.active.get() > 0);
                var duringAlert = notification(userId);
                var healthyDuringAlert = kafka.publish(duringAlert, 0);
                await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
                    assertThat(kafka.inputState(environment.creationGroup(), healthyDuringAlert).completed()).isTrue();
                    stream.assertHealthy();
                    assertThat(stream.notifications()).hasSize(3);
                });
                assertThat(webhook.active.get()).isPositive();
                assertThat(alertFailures()).isEqualTo(alertFailuresBefore);
                assertThat(kafka.inputState(handoffGroup, handoffInput).completed()).isTrue();
                var finalRetry = records(environment.retryTopic(), request).stream()
                    .filter(record -> attempt(record) == 4).findFirst().orElseThrow();
                await().atMost(Duration.ofSeconds(5)).untilAsserted(
                    () -> assertThat(kafka.inputState(environment.creationGroup() + "-retry", finalRetry.position())
                        .completed()).isTrue());
                assertThat(stream.notifications().stream().map(notification -> notification.notificationId()))
                    .containsExactly(expected.id(), database.notifications(duringRetry).getFirst().id(),
                        database.notifications(duringAlert).getFirst().id());
                webhook.release();
                await().atMost(Duration.ofSeconds(8)).until(() -> alertFailures() > alertFailuresBefore);
                evidence.put("alertFailuresAfter", alertFailures());

                assertThat(JSON.readValue(dlt.value(), NotificationCreateRequested.class)).isEqualTo(request);
                assertThat(new String(header(dlt, KafkaHeaders.ORIGINAL_TOPIC), StandardCharsets.UTF_8))
                    .isEqualTo(environment.creationTopic());
                assertThat(ByteBuffer.wrap(header(dlt, KafkaHeaders.ORIGINAL_OFFSET)).getLong())
                    .isEqualTo(original.offset());
                assertThat(records(environment.retryTopic(), request).stream().map(this::attempt)).contains(2, 3, 4);
                assertThat(database.notifications(request)).containsExactly(expected);
                assertThat(records(environment.deliveryTopic(), request)).hasSize(1);
                assertThat(logged("DLT_RECORDED", request)).isTrue();
                evidence.put("recovered", Map.of("input", kafka.inputState(handoffGroup, handoffInput),
                    "originalInput", kafka.inputState(environment.creationGroup(), original),
                    "database", database.notifications(request), "retryRecords",
                    records(environment.retryTopic(), request),
                    "dltRecords", records(environment.dltTopic(), request), "sseNotifications", stream.notifications(),
                    "webhookConnectRequests", List.copyOf(webhook.requests)));
            }
            assertThat(webhook.failure.get()).isNull();
            evidence.put("completed", true);
        } catch (Exception | Error exception) {
            failure = exception;
            throw exception;
        } finally {
            webhook.release();
            try {
                environment.kafkaFaultProxy().setEnabled(Channel.PRODUCER, true);
                environment.report("retry-handoff-" + failedAttempt + "-" + UUID.randomUUID(), evidence);
            } catch (Exception | Error reportFailure) {
                if (failure == null) {
                    throw reportFailure;
                }
                failure.addSuppressed(reportFailure);
            }
        }
    }

    private List<ObservedRecord> records(String topic, NotificationCreateRequested request) throws Exception {
        return environment.kafkaProbe().snapshot(topic).records().stream()
            .filter(
                record -> JSON.readTree(record.value()).path("sourceEventId").asText().equals(request.sourceEventId()))
            .toList();
    }

    private boolean logged(String code, NotificationCreateRequested request) throws IOException {
        return Files.readString(environment.first().log()).lines()
            .anyMatch(line -> line.contains("errorCode=" + code + ",")
                && line.contains("sourceEventId=" + request.sourceEventId() + ","));
    }

    private long alertFailures() throws IOException {
        return Files.readString(environment.first().log()).lines()
            .filter(line -> line.contains("errorCode=ALERT_WEBHOOK_FAILED,")
                && line.contains("originalErrorCode=DLT_RECORDED,"))
            .count();
    }

    private int attempt(ObservedRecord record) {
        return ByteBuffer.wrap(header(record, RetryTopicHeaders.DEFAULT_HEADER_ATTEMPTS)).getInt();
    }

    private byte[] header(ObservedRecord record, String name) {
        return record.headers().stream().filter(header -> header.key().equals(name)).reduce((first, last) -> last)
            .map(value -> Base64.getDecoder().decode(value.valueBase64())).orElseThrow();
    }

    private NotificationCreateRequested notification(long userId) {
        return new NotificationCreateRequested(1, "PR25_HANDOFF:" + UUID.randomUUID(), userId, NotificationType.NOTICE,
            "인계 검증", "원본 보존", null, null, Instant.now().truncatedTo(ChronoUnit.MICROS));
    }

    private static final class WebhookFailureProxy implements AutoCloseable {
        private final ServerSocket server;
        private final ExecutorService handlers = Executors.newVirtualThreadPerTaskExecutor();
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final List<String> requests = new CopyOnWriteArrayList<>();
        private volatile CountDownLatch gate = new CountDownLatch(1);
        private final Thread acceptor;

        private WebhookFailureProxy() {
            try {
                server = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
            } catch (IOException exception) {
                throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR, exception);
            }
            acceptor = Thread.ofVirtual().start(() -> {
                while (!server.isClosed()) {
                    try {
                        var socket = server.accept();
                        handlers.execute(() -> reject(socket));
                    } catch (IOException exception) {
                        if (!server.isClosed()) {
                            failure.compareAndSet(null, exception);
                        }
                    }
                }
            });
        }

        private void reject(Socket socket) {
            boolean entered = false;
            try (socket) {
                socket.setSoTimeout(2000);
                var reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                String request = reader.readLine();
                assertThat(request).isEqualTo("CONNECT discord.com:443 HTTP/1.1");
                requests.add(request);
                String line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {}
                active.incrementAndGet();
                entered = true;
                assertThat(gate.await(15, TimeUnit.SECONDS)).isTrue();
                socket.getOutputStream().write(("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
            } catch (Exception | Error exception) {
                failure.compareAndSet(null, exception);
            } finally {
                if (entered) {
                    active.decrementAndGet();
                }
            }
        }

        private void block() {
            gate = new CountDownLatch(1);
        }

        private void release() {
            gate.countDown();
        }

        @Override
        public void close() throws Exception {
            release();
            server.close();
            assertThat(acceptor.join(Duration.ofSeconds(3))).isTrue();
            handlers.close();
        }
    }
}

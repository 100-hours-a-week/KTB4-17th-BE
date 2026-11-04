package com.team.dating_backend.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.team.dating_backend.notification.dto.response.NotificationEventResponse;
import com.team.dating_backend.notification.support.NotificationBackendProcess;
import com.team.dating_backend.notification.support.NotificationBackendProcess.Termination;
import com.team.dating_backend.notification.support.NotificationIntegrationEnvironment;
import com.team.dating_backend.notification.support.NotificationKafkaFaultProxy.Channel;
import com.team.dating_backend.notification.support.NotificationMultiProcessTestSupport;
import com.team.dating_backend.notification.support.NotificationSseProbe;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.RangeAssignor;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;

@Tag("notification-multiprocess")
@TestMethodOrder(OrderAnnotation.class)
@Timeout(110)
class NotificationMultiProcessLifecycleIntegrationTest extends NotificationMultiProcessTestSupport {

    private Admin admin;
    private Map<String, Object> evidence;
    private String reportName;

    @Override
    protected NotificationIntegrationEnvironment createEnvironment() {
        return new NotificationIntegrationEnvironment(NotificationIntegrationEnvironment.Mode.LIFECYCLE);
    }

    @BeforeAll
    void connectAdmin() {
        admin = Admin.create(Map.of("bootstrap.servers", environment.bootstrapServers(),
            "default.api.timeout.ms", 5000, "request.timeout.ms", 3000));
    }

    @BeforeEach
    void prepareLifecycle(TestInfo test) throws Exception {
        evidence = new LinkedHashMap<>();
        evidence.put("scenario", test.getDisplayName());
        evidence.put("completed", false);
        reportName = "lifecycle-" + UUID.randomUUID();
        if (!environment.first().isAlive()) {
            restartFirst();
        }
        environment.assertGroupsReady();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(state(first)).containsEntry("accepting", 1.0).containsEntry("connections", 0.0);
            assertThat(state(second)).containsEntry("accepting", 1.0).containsEntry("connections", 0.0);
        });
    }

    @AfterEach
    void restoreAndReport() throws Exception {
        try {
            environment.kafkaFaultProxy().setEnabled(Channel.CONSUMER, true);
        } finally {
            evidence.put("firstHistory", environment.first().history());
            evidence.put("secondHistory", environment.second().history());
            environment.report(reportName, evidence);
        }
    }

    @AfterAll
    void closeAdmin() {
        if (admin != null) {
            admin.close(Duration.ofSeconds(5));
        }
    }

    @Test
    @Order(1)
    void 새_프로세스는_준비_전_구독을_거절하고_준비_후_기존_서버와_함께_수신한다() throws Exception {
        long recipient = activeUser();
        var oldStream = subscribe(second, recipient);
        var backend = environment.first();
        String previousGroup = deliveryGroup(backend);
        long previousPid = backend.pid();
        backend.requestGracefulShutdown();
        assertThat(backend.awaitExit(Duration.ofSeconds(15))).isTrue();

        environment.kafkaFaultProxy().setEnabled(Channel.CONSUMER, false);
        try {
            environment.restartFirst(false);
            first = backend.address();
            assertThat(backend.pid()).isNotEqualTo(previousPid);
            error(request(first, "GET", NOTIFICATIONS + "/stream", token(recipient), null),
                503, "NOTIFICATION_STREAM_NOT_READY");
            assertThat(state(first)).containsEntry("ready", 0.0).containsEntry("accepting", 0.0)
                .containsEntry("connections", 0.0);
            evidence.put("beforeReady", Map.of("state", state(first), "previousGroup", previousGroup,
                "previousPid", previousPid, "newPid", backend.pid()));
            var beforeReady = likeNotification(second, activeUser(), recipient);
            var beforeStored = stored(beforeReady);
            awaitNotifications(oldStream, List.of(beforeStored));
            evidence.put("beforeReadyNotification", Map.of("request", beforeReady, "stored", beforeStored));

            environment.kafkaFaultProxy().setEnabled(Channel.CONSUMER, true);
            environment.assertGroupsReady();
            awaitAccepting(first);
            var newStream = subscribe(first, recipient);
            String newGroup = deliveryGroup(backend);
            String oldGroup = deliveryGroup(environment.second());
            assertThat(newGroup).isNotEqualTo(previousGroup).isNotEqualTo(oldGroup);
            assertFullAssignment(newGroup);
            assertFullAssignment(oldGroup);

            var afterReady = likeNotification(second, activeUser(), recipient);
            var afterStored = stored(afterReady);
            environment.awaitProcessed();
            awaitNotifications(newStream, List.of(afterStored));
            awaitNotifications(oldStream, List.of(beforeStored, afterStored));
            evidence.put("afterReady", Map.of("state", state(first), "newGroup", groupSnapshot(newGroup),
                "oldGroup", groupSnapshot(oldGroup), "request", afterReady, "stored", afterStored,
                "connections", List.of(streamSnapshot(newStream), streamSnapshot(oldStream))));
            evidence.put("completed", true);
        } finally {
            environment.kafkaFaultProxy().setEnabled(Channel.CONSUMER, true);
        }
    }

    @Test
    @Order(2)
    void 실제_Partition_재할당_중에도_기존_연결과_heartbeat를_유지하고_회복_후_수신한다() throws Exception {
        long recipient = activeUser();
        var onFirst = subscribe(first, recipient);
        var onSecond = subscribe(second, recipient);
        String connectionId = onFirst.connectionId();
        String firstGroup = deliveryGroup(environment.first());
        String secondGroup = deliveryGroup(environment.second());
        long pid = environment.first().pid();
        try (var borrower = new PartitionBorrower(environment.bootstrapServers(), firstGroup,
            environment.deliveryTopic())) {
            await().atMost(Duration.ofSeconds(25)).untilAsserted(() -> {
                borrower.assertHealthy();
                assertThat(borrower.assignment.get()).hasSize(1);
                assertThat(describe(firstGroup).members()).hasSize(2)
                    .allSatisfy(member -> assertThat(member.assignment().topicPartitions()).hasSize(1));
                assertThat(state(first)).containsEntry("ready", 0.0).containsEntry("accepting", 0.0)
                    .containsEntry("connections", 1.0);
            });
            error(request(first, "GET", NOTIFICATIONS + "/stream", token(recipient), null),
                503, "NOTIFICATION_STREAM_NOT_READY");
            int heartbeats = onFirst.heartbeats().size();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                onFirst.assertHealthy();
                assertThat(onFirst.remoteEndedAt()).isNull();
                assertThat(onFirst.heartbeats()).hasSizeGreaterThan(heartbeats);
            });
            assertFullAssignment(secondGroup);
            long otherRecipient = activeUser();
            var healthyStream = subscribe(second, otherRecipient);
            var healthyRequest = likeNotification(second, activeUser(), otherRecipient);
            var healthyStored = stored(healthyRequest);
            awaitNotifications(healthyStream, List.of(healthyStored));
            evidence.put("duringReassignment", Map.of("state", state(first),
                "firstGroup", groupSnapshot(firstGroup), "secondGroup", groupSnapshot(secondGroup),
                "connection", streamSnapshot(onFirst), "healthyRequest", healthyRequest,
                "healthyStored", healthyStored, "healthyConnection", streamSnapshot(healthyStream)));
        }

        environment.assertGroupsReady();
        awaitAccepting(first);
        assertThat(environment.first().pid()).isEqualTo(pid);
        assertThat(deliveryGroup(environment.first())).isEqualTo(firstGroup);
        assertThat(onFirst.connectionId()).isEqualTo(connectionId);
        var additional = subscribe(first, recipient);
        assertThat(additional.connectionId()).isNotEqualTo(connectionId);
        var recovered = likeNotification(second, activeUser(), recipient);
        var recoveredStored = stored(recovered);
        environment.awaitProcessed();
        for (var connection : List.of(onFirst, onSecond, additional)) {
            awaitNotifications(connection, List.of(recoveredStored));
        }
        evidence.put("afterReassignment", Map.of("state", state(first), "group", groupSnapshot(firstGroup),
            "request", recovered, "stored", recoveredStored,
            "connections", List.of(streamSnapshot(onFirst), streamSnapshot(onSecond), streamSnapshot(additional))));
        evidence.put("completed", true);
    }

    @Test
    @Order(3)
    void SIGTERM_드레인_중_기존_수신을_유지하고_연결과_Consumer_종료_후_다른_서버로_재구독한다() throws Exception {
        long recipient = activeUser();
        var onFirst = subscribe(first, recipient);
        var onSecond = subscribe(second, recipient);
        var warmup = likeNotification(second, activeUser(), recipient);
        var warmupStored = stored(warmup);
        awaitNotifications(onFirst, List.of(warmupStored));
        awaitNotifications(onSecond, List.of(warmupStored));
        environment.awaitProcessed();
        var backend = environment.first();
        String firstGroup = deliveryGroup(backend);
        String secondGroup = deliveryGroup(environment.second());
        onFirst.expectRemoteEnd();
        long startedAt = System.nanoTime();
        backend.requestGracefulShutdown();
        Instant requestedAt = backend.history().getLast().shutdownRequestedAt();
        backend.requestGracefulShutdown();
        assertThat(backend.history().getLast().shutdownRequestedAt()).isEqualTo(requestedAt);
        assertThat(backend.awaitExit(Duration.ZERO)).isFalse();
        assertThat(backend.history().getLast().termination()).isEqualTo(Termination.SHUTDOWN_REQUESTED);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(Files.readString(backend.log())).contains("SSE drain started.");
            assertThat(state(first)).containsEntry("ready", 1.0).containsEntry("accepting", 0.0)
                .containsEntry("connections", 1.0);
        });
        error(request(first, "GET", NOTIFICATIONS + "/stream", token(recipient), null),
            503, "NOTIFICATION_STREAM_NOT_READY");
        int heartbeats = onFirst.heartbeats().size();
        var onNewServer = subscribe(second, recipient);
        var duringDrain = likeNotification(second, activeUser(), recipient);
        var duringStored = stored(duringDrain);
        awaitNotifications(onFirst, List.of(warmupStored, duringStored));
        awaitNotifications(onSecond, List.of(warmupStored, duringStored));
        awaitNotifications(onNewServer, List.of(duringStored));
        await().atMost(Duration.ofSeconds(4)).untilAsserted(() -> {
            onFirst.assertHealthy();
            assertThat(onFirst.remoteEndedAt()).isNull();
            assertThat(onFirst.heartbeats()).hasSizeGreaterThan(heartbeats);
        });
        assertFullAssignment(firstGroup);
        evidence.put("duringDrain", Map.of("state", state(first), "firstGroup", groupSnapshot(firstGroup),
            "request", duringDrain, "stored", duringStored, "connection", streamSnapshot(onFirst)));

        assertThat(backend.awaitExit(Duration.ofSeconds(20))).isTrue();
        long exitObservedNanos = System.nanoTime() - startedAt;
        onFirst.assertHealthy();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(onFirst.remoteEndedAt()).isNotNull());
        var generation = backend.history().getLast();
        assertThat(generation.termination()).isEqualTo(Termination.GRACEFUL);
        assertThat(generation.exitCode()).isIn(0, 143);
        String log = Files.readString(backend.log());
        String summary = log.lines().filter(line -> line.contains("SSE shutdown finished.")).findFirst().orElseThrow();
        assertThat(summary).contains("connections=1,", "completeRequests=1,", "incomplete=0,", "failed=0,",
            "consumerStopResult=STOPPED,", "interrupted=false");
        String closed = log.lines().filter(line -> line.contains("SSE connection closed.")
            && line.contains("connectionId=" + onFirst.connectionId() + ",") && line.contains("reason=SHUTDOWN,"))
            .findFirst().orElseThrow();
        assertThat(log.indexOf(closed)).isLessThan(log.indexOf(summary));
        await().atMost(Duration.ofSeconds(10))
            .untilAsserted(() -> assertThat(describe(firstGroup).members()).isEmpty());
        assertFullAssignment(secondGroup);
        assertThat(state(second)).containsEntry("ready", 1.0).containsEntry("accepting", 1.0);

        var reconnected = subscribe(second, recipient);
        assertThat(reconnected.connectionId()).isNotEqualTo(onFirst.connectionId());
        var afterExit = likeNotification(second, activeUser(), recipient);
        var afterStored = stored(afterExit);
        environment.awaitProcessed();
        awaitNotifications(onSecond, List.of(warmupStored, duringStored, afterStored));
        awaitNotifications(onNewServer, List.of(duringStored, afterStored));
        awaitNotifications(reconnected, List.of(afterStored));
        assertThat(onFirst.notifications()).containsExactly(warmupStored, duringStored);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE user_id = ?", Long.class, recipient))
            .isEqualTo(3);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM notifications WHERE user_id = ? AND (read_at IS NOT NULL OR deleted_at IS NOT NULL)",
            Long.class, recipient)).isZero();
        evidence.put("afterExit", Map.of("generation", generation, "observedShutdownNanos", exitObservedNanos,
            "shutdownSummary", summary, "connectionClosedLog", closed, "firstGroup", groupSnapshot(firstGroup),
            "secondGroup", groupSnapshot(secondGroup), "request", afterExit, "stored", afterStored,
            "connections", List.of(streamSnapshot(onFirst), streamSnapshot(onSecond),
                streamSnapshot(onNewServer), streamSnapshot(reconnected))));
        evidence.put("completed", true);
    }

    private void awaitNotifications(NotificationSseProbe stream, List<NotificationEventResponse> expected) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            stream.assertHealthy();
            assertThat(stream.notifications()).containsExactlyElementsOf(expected);
        });
    }

    private void awaitAccepting(URI server) {
        await().atMost(Duration.ofSeconds(10))
            .untilAsserted(() -> assertThat(state(server)).containsEntry("ready", 1.0).containsEntry("accepting", 1.0));
    }

    private Map<String, Double> state(URI server) throws Exception {
        var request = HttpRequest.newBuilder(server.resolve("/actuator/prometheus"))
            .timeout(Duration.ofSeconds(5)).header("Accept", "text/plain").GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        var state = new LinkedHashMap<String, Double>();
        for (String name : List.of("ready", "accepting", "connections")) {
            String meter = name.equals("ready") ? "notification_delivery_ready" : "notification_sse_" + name;
            var matcher = Pattern.compile("(?m)^" + meter + "(?:\\{[^\\n]*})?\\s+(\\S+)").matcher(response.body());
            assertThat(matcher.find()).as("meter=%s", meter).isTrue();
            state.put(name, Double.parseDouble(matcher.group(1)));
        }
        return state;
    }

    private String deliveryGroup(NotificationBackendProcess backend) throws Exception {
        var groups = environment.deliveryGroups().stream()
            .filter(group -> group.startsWith(backend.deliveryGroupPrefix() + "-")).toList();
        assertThat(groups).hasSize(1);
        return groups.getFirst();
    }

    private ConsumerGroupDescription describe(String group) throws Exception {
        return admin.describeConsumerGroups(List.of(group)).all().get(5, TimeUnit.SECONDS).get(group);
    }

    private void assertFullAssignment(String group) throws Exception {
        assertThat(describe(group).members()).hasSize(1)
            .allSatisfy(member -> assertThat(member.assignment().topicPartitions()).containsExactlyInAnyOrder(
                new TopicPartition(environment.deliveryTopic(), 0),
                new TopicPartition(environment.deliveryTopic(), 1)));
    }

    private Map<String, Object> groupSnapshot(String group) throws Exception {
        return Map.of("group", group, "members",
            describe(group).members().stream()
                .map(member -> Map.of("memberId", member.consumerId(), "clientId", member.clientId(),
                    "partitions",
                    member.assignment().topicPartitions().stream().map(TopicPartition::toString).sorted().toList()))
                .toList());
    }

    private Map<String, Object> streamSnapshot(NotificationSseProbe stream) {
        var result = new LinkedHashMap<String, Object>();
        result.put("server", stream.server());
        result.put("connectionId", stream.connectionId());
        result.put("notifications", stream.notifications());
        result.put("heartbeats", stream.heartbeats());
        result.put("remoteEndedAt", stream.remoteEndedAt());
        return result;
    }

    private static final class PartitionBorrower implements AutoCloseable {

        private final KafkaConsumer<byte[], byte[]> consumer;
        private final AtomicReference<Set<TopicPartition>> assignment = new AtomicReference<>(Set.of());
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final Thread thread;
        private volatile boolean closed;

        private PartitionBorrower(String bootstrap, String group, String topic) {
            consumer = new KafkaConsumer<>(Map.of("bootstrap.servers", bootstrap, "group.id", group,
                "client.id", "pr26-partition-borrower", "enable.auto.commit", false, "auto.offset.reset", "latest",
                "key.deserializer", ByteArrayDeserializer.class, "value.deserializer", ByteArrayDeserializer.class,
                "partition.assignment.strategy", RangeAssignor.class.getName(), "default.api.timeout.ms", 5000));
            thread = Thread.ofVirtual().name("pr26-partition-borrower").start(() -> {
                try {
                    consumer.subscribe(List.of(topic));
                    while (!closed) {
                        consumer.poll(Duration.ofMillis(100));
                        assignment.set(Set.copyOf(consumer.assignment()));
                    }
                } catch (WakeupException exception) {
                    if (!closed) {
                        failure.compareAndSet(null, exception);
                    }
                } catch (Throwable exception) {
                    failure.compareAndSet(null, exception);
                } finally {
                    try {
                        consumer.close(Duration.ofSeconds(5));
                    } catch (Throwable exception) {
                        failure.compareAndSet(null, exception);
                    }
                }
            });
        }

        private void assertHealthy() {
            assertThat(failure.get()).isNull();
        }

        @Override
        public void close() throws InterruptedException {
            closed = true;
            consumer.wakeup();
            assertThat(thread.join(Duration.ofSeconds(10))).isTrue();
            assertHealthy();
        }
    }
}

package com.team.dating_backend.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.team.dating_backend.notification.support.NotificationIntegrationEnvironment;
import com.team.dating_backend.notification.support.NotificationKafkaProbe;
import com.team.dating_backend.notification.support.NotificationMultiProcessTestSupport;
import com.team.dating_backend.notification.support.NotificationSseProbe;
import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import com.team.dating_backend.notification.enums.NotificationType;
import com.team.dating_backend.notification.support.NotificationKafkaProbe.Position;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.Timeout;

@Tag("notification-multiprocess")
@Timeout(180)
class NotificationMultiProcessBrokerIntegrationTest extends NotificationMultiProcessTestSupport {

    private NotificationKafkaProbe kafka;
    private Map<String, Object> evidence;
    private String reportName;

    @Override
    protected NotificationIntegrationEnvironment createEnvironment() {
        return new NotificationIntegrationEnvironment(NotificationIntegrationEnvironment.Mode.BROKER);
    }

    @BeforeEach
    void prepareBroker(TestInfo test) {
        kafka = environment.kafkaProbe();
        evidence = new LinkedHashMap<>();
        evidence.put("scenario", test.getDisplayName());
        evidence.put("completed", false);
        reportName = "broker-recovery-" + UUID.randomUUID();
    }

    @AfterEach
    void reportBroker() throws Exception {
        evidence.put("brokerHistory", environment.brokerHistory());
        evidence.put("firstHistory", environment.first().history());
        evidence.put("secondHistory", environment.second().history());
        environment.report(reportName, evidence);
    }

    @Test
    void 실제_리더_Broker_중단과_동일_노드_복귀_전후에_업무_알림이_양쪽_SSE로_전달된다() throws Exception {
        long recipient = activeUser();
        var onFirst = subscribe(first, recipient);
        var onSecond = subscribe(second, recipient);
        var expected = new ArrayList<Expected>();
        expected.add(likeNotification(first, activeUser(), recipient));
        checkpoint("beforeStop", expected, onFirst, onSecond);
        var original = kafka.snapshot(environment.creationTopic()).records().stream()
            .filter(record -> JSON.readTree(record.value()).path("sourceEventId").asText()
                .equals(expected.getFirst().sourceEventId()))
            .findFirst().orElseThrow();
        var before = kafka.topology();
        var inputPartition = before.partitions().stream()
            .filter(partition -> partition.topic().equals(original.position().topic())
                && partition.partition() == original.position().partition())
            .findFirst().orElseThrow();
        assertThat(inputPartition.leaderNodeId()).isNotNull();
        assertThat(before.partitions()).hasSize(8)
            .allSatisfy(partition -> assertThat(partition.inSyncReplicas()).containsExactly(1, 2, 3));
        int nodeId = inputPartition.leaderNodeId();
        evidence.put("target", Map.of("input", original, "partition", inputPartition,
            "broker", environment.brokerState(nodeId)));
        Throwable failure = null;
        try {
            environment.stopBroker(nodeId);
            awaitTwoReplicas(nodeId, before.clusterId());
            expected.add(likeNotification(first, activeUser(), recipient));
            checkpoint("whileStopped", expected, onFirst, onSecond);
            var duringInput = kafka.snapshot(environment.creationTopic()).records().stream()
                .filter(record -> JSON.readTree(record.value()).path("sourceEventId").asText()
                    .equals(expected.getLast().sourceEventId()))
                .findFirst().orElseThrow();
            assertThat(duringInput.position().partition()).isEqualTo(original.position().partition());
            assertThat(duringInput.key()).isEqualTo(original.key());
        } catch (Exception | Error exception) {
            failure = exception;
            throw exception;
        } finally {
            try {
                environment.restoreBroker(nodeId);
            } catch (Exception | Error restoreFailure) {
                if (failure == null) {
                    throw restoreFailure;
                }
                failure.addSuppressed(restoreFailure);
            }
        }
        expected.add(likeNotification(first, activeUser(), recipient));
        checkpoint("afterRestore", expected, onFirst, onSecond);
        assertThat(kafka.snapshot(environment.creationTopic()).records()).contains(original);
        assertThat(kafka.topology().clusterId()).isEqualTo(before.clusterId());
        evidence.put("completed", true);
    }

    @Test
    void 한_Broker_중단_중_쌓인_20개_입력을_보존하고_잠금_해제_후_모두_전달한다() throws Exception {
        long recipient = activeUser();
        var onFirst = subscribe(first, recipient);
        var onSecond = subscribe(second, recipient);
        var expected = new ArrayList<Expected>();
        var warmup = notice(recipient);
        Position warmupInput = kafka.publish(warmup);
        expected.add(new Expected(warmup.sourceEventId(), recipient, "NOTICE"));
        checkpoint("beforeBacklog", expected, onFirst, onSecond);
        var before = kafka.topology();
        int nodeId = before.partitions().stream()
            .filter(partition -> partition.topic().equals(warmupInput.topic())
                && partition.partition() == warmupInput.partition())
            .findFirst().orElseThrow().leaderNodeId();
        Throwable failure = null;
        try {
            environment.stopBroker(nodeId);
            awaitTwoReplicas(nodeId, before.clusterId());
            evidence.put("resourcesBefore", resourceSample());
            var requests = new ArrayList<NotificationCreateRequested>();
            var inputs = new ArrayList<Position>();
            var sentAt = new HashMap<String, Long>();
            long inputStartedAt = System.nanoTime();
            long releaseRequestedAt;
            try (var lock = environment.databaseProbe().lockRecipient(recipient)) {
                for (int index = 0; index < 20; index++) {
                    var request = notice(recipient);
                    requests.add(request);
                    expected.add(new Expected(request.sourceEventId(), recipient, "NOTICE"));
                    sentAt.put(request.sourceEventId(), System.nanoTime());
                    var input = kafka.publish(request);
                    assertThat(input.topicPartition()).isEqualTo(warmupInput.topicPartition());
                    inputs.add(input);
                }
                var waits = lock.awaitWaitingTransactions(1);
                var progress = kafka.progress(environment.creationGroup(), environment.creationTopic());
                var blocked = progress.partitions().stream()
                    .filter(partition -> partition.partition() == warmupInput.partition()).findFirst().orElseThrow();
                assertThat(blocked.committedOffset()).isEqualTo(warmupInput.offset() + 1);
                assertThat(blocked.endOffset()).isEqualTo(warmupInput.offset() + 21);
                assertThat(blocked.lag()).isEqualTo(20);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE user_id = ?", Long.class,
                    recipient)).isEqualTo(1);
                assertThat(onFirst.notifications()).hasSize(1);
                assertThat(onSecond.notifications()).hasSize(1);
                evidence.put("blockedBacklog", Map.of("requests", requests, "inputs", inputs, "progress", progress,
                    "lockWaits", waits, "inputAcknowledgedNanos", System.nanoTime() - inputStartedAt));
                releaseRequestedAt = System.nanoTime();
            }
            long releaseReturnedAt = System.nanoTime();
            checkpoint("afterCatchUp", expected, onFirst, onSecond);
            var arrivals = new ArrayList<Map<String, Object>>();
            long lastArrival = releaseRequestedAt;
            for (var connection : List.of(onFirst, onSecond)) {
                var firstArrivals = new HashMap<Long, Long>();
                connection.receipts().forEach(receipt -> firstArrivals.merge(receipt.notificationId(),
                    receipt.receivedNanos(), Math::min));
                for (var request : requests) {
                    long id = stored(new Expected(request.sourceEventId(), recipient, "NOTICE")).notificationId();
                    Long arrived = firstArrivals.get(id);
                    assertThat(arrived).isNotNull().isGreaterThanOrEqualTo(releaseRequestedAt);
                    lastArrival = Math.max(lastArrival, arrived);
                    arrivals.add(Map.of("sourceEventId", request.sourceEventId(), "notificationId", id,
                        "connectionId", connection.connectionId(), "sendToReceiptNanos",
                        arrived - sentAt.get(request.sourceEventId()),
                        "releaseRequestToReceiptNanos", arrived - releaseRequestedAt));
                }
            }
            long caughtUpNanos = lastArrival - releaseRequestedAt;
            assertThat(caughtUpNanos).isPositive();
            evidence.put("measurement", Map.of("requestCount", 20, "receiptCount", arrivals.size(),
                "releaseCallNanos", releaseReturnedAt - releaseRequestedAt, "lastFirstReceiptNanos", caughtUpNanos,
                "observedRequestsPerSecond", 20.0 * 1_000_000_000 / caughtUpNanos, "arrivals", arrivals,
                "resourcesAfter", resourceSample()));
        } catch (Exception | Error exception) {
            failure = exception;
            throw exception;
        } finally {
            try {
                long restoreStarted = System.nanoTime();
                environment.restoreBroker(nodeId);
                evidence.put("restoreObservedNanos", System.nanoTime() - restoreStarted);
            } catch (Exception | Error restoreFailure) {
                if (failure == null) {
                    throw restoreFailure;
                }
                failure.addSuppressed(restoreFailure);
            }
        }
        checkpoint("afterBacklogRestore", expected, onFirst, onSecond);
        evidence.put("completed", true);
    }

    private NotificationCreateRequested notice(long recipient) {
        return new NotificationCreateRequested(1, "PR27_BACKLOG:" + UUID.randomUUID(), recipient,
            NotificationType.NOTICE, "복구 확인", "처리 대기 알림", null, null, Instant.now().truncatedTo(ChronoUnit.MICROS));
    }

    private void awaitTwoReplicas(int nodeId, String clusterId) {
        await().atMost(Duration.ofSeconds(45)).untilAsserted(() -> {
            var down = kafka.topologyWithConsumerOffsets();
            assertThat(down.clusterId()).isEqualTo(clusterId);
            assertThat(down.partitions()).hasSize(11).allSatisfy(partition -> {
                assertThat(partition.leaderNodeId()).isNotNull().isNotEqualTo(nodeId);
                assertThat(partition.replicas()).containsExactly(1, 2, 3);
                assertThat(partition.inSyncReplicas()).hasSize(2).doesNotContain(nodeId);
            });
        });
        environment.assertGroupsReady();
    }

    private List<Map<String, Object>> resourceSample() throws Exception {
        var samples = new ArrayList<Map<String, Object>>();
        for (var backend : List.of(environment.first(), environment.second())) {
            var sample = new LinkedHashMap<String, Object>();
            sample.put("pid", backend.pid());
            sample.put("observedAt", Instant.now());
            sample.put("cpuTimeNanos", ProcessHandle.of(backend.pid()).orElseThrow().info().totalCpuDuration()
                .map(Duration::toNanos).orElse(null));
            var builder = new ProcessBuilder("ps", "-o", "%cpu=,rss=", "-p", Long.toString(backend.pid()));
            builder.environment().put("LC_ALL", "C");
            var command = builder.start();
            try {
                assertThat(command.waitFor(5, TimeUnit.SECONDS)).isTrue();
                assertThat(command.exitValue()).isZero();
                var values = new String(command.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8).strip().split("\\s+");
                assertThat(values).hasSize(2);
                sample.put("psCpuPercent", Double.parseDouble(values[0]));
                sample.put("rssKiB", Long.parseLong(values[1]));
            } finally {
                if (command.isAlive()) {
                    command.destroyForcibly();
                    command.waitFor(5, TimeUnit.SECONDS);
                }
            }
            samples.add(sample);
        }
        return samples;
    }

    private void checkpoint(String phase, List<Expected> expected, NotificationSseProbe... connections)
        throws Exception {
        var stored = expected.stream().map(this::stored).toList();
        var ids = stored.stream().map(notification -> notification.notificationId()).toList();
        assertThat(jdbc.queryForList("SELECT id FROM notifications WHERE user_id = ?", Long.class,
            expected.getFirst().recipientUserId())).containsExactlyInAnyOrderElementsOf(ids);
        await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            for (var connection : connections) {
                connection.assertHealthy();
                assertThat(connection.notifications()).containsAll(stored).isSubsetOf(stored);
            }
        });
        var streams = new ArrayList<Map<String, Object>>();
        for (var connection : connections) {
            streams.add(Map.of("connectionId", connection.connectionId(), "server", connection.server(),
                "notificationIdCounts", connection.notifications().stream().collect(Collectors.groupingBy(
                    notification -> notification.notificationId(), Collectors.counting()))));
        }
        var state = new LinkedHashMap<String, Object>();
        state.put("requests", List.copyOf(expected));
        state.put("stored", stored);
        state.put("connections", streams);
        state.put("topology", kafka.topologyWithConsumerOffsets());
        evidence.put(phase, state);
        await().atMost(Duration.ofSeconds(60)).ignoreExceptions().untilAsserted(() -> {
            var groups = new ArrayList<NotificationKafkaProbe.GroupProgress>();
            groups.add(kafka.progress(environment.creationGroup(), environment.creationTopic()));
            for (String group : environment.deliveryGroups()) {
                groups.add(kafka.progress(group, environment.deliveryTopic()));
            }
            state.put("groups", List.copyOf(groups));
            assertThat(groups).hasSize(3).allSatisfy(group -> {
                assertThat(group.partitions()).hasSize(2).allSatisfy(partition -> {
                    if (partition.endOffset() > 0) {
                        assertThat(partition.committedOffset()).isNotNull().isEqualTo(partition.endOffset());
                        assertThat(partition.lag()).isZero();
                    }
                });
            });
        });
        assertThat(kafka.snapshot(environment.retryTopic()).records()).isEmpty();
        assertThat(kafka.snapshot(environment.dltTopic()).records()).isEmpty();
    }
}

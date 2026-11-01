package com.team.dating_backend.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import com.team.dating_backend.notification.support.NotificationDatabaseProbe;
import com.team.dating_backend.notification.support.NotificationDatabaseProbe.StoredOutbox;
import com.team.dating_backend.notification.support.NotificationIntegrationEnvironment;
import com.team.dating_backend.notification.support.NotificationIntegrationEnvironment.Mode;
import com.team.dating_backend.notification.support.NotificationKafkaFaultProxy.Channel;
import com.team.dating_backend.notification.support.NotificationKafkaProbe;
import com.team.dating_backend.notification.support.NotificationKafkaProbe.ObservedRecord;
import com.team.dating_backend.notification.support.NotificationMultiProcessTestSupport;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("notification-multiprocess")
class NotificationMultiProcessOutboxPublicationIntegrationTest extends NotificationMultiProcessTestSupport {

    private static final long PRODUCER_DELIVERY_TIMEOUT_MS = 10_000L;
    private NotificationDatabaseProbe database;
    private NotificationKafkaProbe kafka;
    private Map<String, Object> evidence;
    private String scenario;

    @Override
    protected NotificationIntegrationEnvironment createEnvironment() {
        return new NotificationIntegrationEnvironment(Mode.RECOVERY, Map.of(
            "OUTBOX_RELAY_ENABLED", "false",
            "OUTBOX_FIXED_DELAY_MS", "100",
            "OUTBOX_CLAIM_DURATION_MS", "30000",
            "PRODUCER_DELIVERY_TIMEOUT_MS", Long.toString(PRODUCER_DELIVERY_TIMEOUT_MS)));
    }

    @BeforeEach
    void prepareScenario(TestInfo test) throws Exception {
        database = environment.databaseProbe();
        kafka = environment.kafkaProbe();
        evidence = new LinkedHashMap<>();
        scenario = "outbox-publication-" + UUID.randomUUID();
        evidence.put("testMethod", test.getTestMethod().orElseThrow().getName());
        environment.kafkaFaultProxy().setEnabled(Channel.PRODUCER, true);
        environment.kafkaFaultProxy().setEnabled(Channel.CONSUMER, true);
        if (environment.first().isAlive()) {
            environment.first().requestGracefulShutdown();
            assertThat(environment.first().awaitExit(Duration.ofSeconds(20))).isTrue();
        }
        restartRelay(false);
        awaitCreationCommitted();
        evidence.put("startedAt", Instant.now());
        evidence.put("initialProcesses", List.of(environment.first().history(), environment.second().history()));
        evidence.put("producerDeliveryTimeoutMs", PRODUCER_DELIVERY_TIMEOUT_MS);
    }

    @AfterEach
    void restoreAndRecord() throws Exception {
        try {
            environment.kafkaFaultProxy().setEnabled(Channel.PRODUCER, true);
            environment.kafkaFaultProxy().setEnabled(Channel.CONSUMER, true);
        } finally {
            evidence.put("finishedAt", Instant.now());
            evidence.put("processes", List.of(environment.first().history(), environment.second().history()));
            evidence.put("routes", environment.kafkaFaultProxy().description());
            environment.report(scenario, evidence);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"likes", "notification_request_outbox"})
    void 업무와_Outbox의_실제_DB_저장_실패는_HTTP_요청_전체를_롤백한다(String table) throws Exception {
        long sender = activeUser();
        long recipient = activeUser();
        String column = table.equals("likes") ? "receiver_id" : "recipient_user_id";
        String constraint = "test_reject_outbox_publication";
        jdbc.execute("ALTER TABLE " + table + " ADD CONSTRAINT " + constraint
            + " CHECK (" + column + " <> " + recipient + ")");
        try {
            var response = request(first, "POST", "/api/v1/likes", token(sender), Map.of("receiverId", recipient));
            error(response, 500, "INTERNAL_SERVER_ERROR");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM likes WHERE sender_id = ? AND receiver_id = ?",
                Integer.class, sender, recipient)).isZero();
            assertThat(
                jdbc.queryForObject("SELECT COUNT(*) FROM notification_request_outbox WHERE recipient_user_id = ?",
                    Integer.class, recipient))
                .isZero();
            assertThat(recordsForRecipient(recipient)).isEmpty();
            evidence.put("rejectedTable", table);
            evidence.put("responseStatus", response.statusCode());
            evidence.put("rollbackObserved", true);
        } finally {
            jdbc.execute("ALTER TABLE " + table + " DROP CHECK " + constraint);
        }
        var expected = likeNotification(first, sender, recipient);
        var saved = outbox(expected);
        assertPendingUnclaimed(saved);
        assertThat(records(expected)).isEmpty();
        evidence.put("successfulRetry", saved);
        evidence.put("completed", true);
    }

    @Test
    void 업무와_Outbox_커밋_후_최초_발행_전_JVM을_종료해도_재시작_후_원본을_발행한다() throws Exception {
        long sender = activeUser();
        long recipient = activeUser();
        var expected = likeNotification(first, sender, recipient);
        var before = outbox(expected);
        assertPendingUnclaimed(before);
        assertThat(records(expected)).isEmpty();
        long killedPid = environment.first().pid();
        environment.killFirst();

        assertThat(environment.first().isAlive()).isFalse();
        assertThat(outbox(expected)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM likes WHERE sender_id = ? AND receiver_id = ?",
            Integer.class, sender, recipient)).isEqualTo(1);
        assertThat(records(expected)).isEmpty();
        evidence.put("beforeKill", before);
        evidence.put("killedPid", killedPid);

        restartRelay(true);
        assertThat(environment.first().pid()).isNotEqualTo(killedPid);
        var published = awaitPublished(expected);
        assertOriginal(before, published);
        assertThat(published.failureCount()).isZero();
        assertRequests(before, records(expected), 1);
        awaitCreationCommitted();
        evidence.put("afterRecovery", published);
        evidence.put("creationRecords", records(expected));
        evidence.put("completed", true);
    }

    @Test
    void Kafka_성공_후_PUBLISHED_기록_전_JVM을_종료하면_만료_후_동일_요청을_재발행한다() throws Exception {
        var expected = likeNotification(first, activeUser(), activeUser());
        var before = outbox(expected);
        assertPendingUnclaimed(before);
        environment.killFirst();
        environment.kafkaFaultProxy().setEnabled(Channel.PRODUCER, false);
        try (var claimGate = database.lockOutbox(before.id())) {
            restartRelay(true);
            evidence.put("claimWaits", claimGate.awaitWaitingTransactions(1));
            assertThat(records(expected)).isEmpty();
        }

        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(25)).untilAsserted(
            () -> assertThat(outbox(expected).processingToken()).isNotNull());
        StoredOutbox abandoned;
        try (var resultGate = database.lockOutbox(before.id())) {
            environment.kafkaFaultProxy().setEnabled(Channel.PRODUCER, true);
            await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> assertRequests(before, records(expected), 1));
            evidence.put("resultWaits", resultGate.awaitWaitingTransactions(1));
            var dump = environment.first().dumpThreads("outbox-before-published");
            assertThat(Files.readString(dump)).contains("notification-outbox-",
                "com.team.dating_backend.notification.service.NotificationOutboxClaimService.markPublished");
            abandoned = outbox(expected);
            assertThat(abandoned.status()).isEqualTo("PENDING");
            assertThat(abandoned.failureCount()).isZero();
            assertThat(abandoned.publishedAt()).isNull();
            assertThat(abandoned.processingToken()).isNotNull();
            assertThat(abandoned.processingUntil()).isAfter(dbNow());
            evidence.put("threadDump", dump.toString());
            evidence.put("beforeKill", abandoned);
            evidence.put("creationRecordsBeforeKill", records(expected));
            evidence.put("killedPid", environment.first().pid());
            environment.killFirst();
            assertThat(outbox(expected)).isEqualTo(abandoned);
        }

        restartRelay(true);
        var published = awaitPublished(expected);
        assertOriginal(before, published);
        assertThat(published.failureCount()).isZero();
        assertThat(published.publishedAt()).isAfterOrEqualTo(abandoned.processingUntil());
        assertRequests(before, records(expected), 2);
        awaitCreationCommitted();
        evidence.put("afterRecovery", published);
        evidence.put("creationRecordsAfterRecovery", records(expected));
        evidence.put("completed", true);
    }

    @Test
    void Producer_재시도_기간보다_긴_Kafka_장애에도_커밋된_요청을_보존하고_복구한다() throws Exception {
        environment.killFirst();
        restartRelay(true);
        var warmup = likeNotification(first, activeUser(), activeUser());
        awaitPublished(warmup);
        awaitCreationCommitted();
        environment.kafkaFaultProxy().setEnabled(Channel.PRODUCER, false);
        long disabledAt = System.nanoTime();
        try {
            var expected = likeNotification(first, activeUser(), activeUser());
            var before = outbox(expected);
            var faultDuration = Duration.ofMillis(PRODUCER_DELIVERY_TIMEOUT_MS + 2000L);
            await().atMost(Duration.ofSeconds(25)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
                assertThat(System.nanoTime() - disabledAt).isGreaterThanOrEqualTo(faultDuration.toNanos());
                var pending = outbox(expected);
                assertThat(pending.status()).isEqualTo("PENDING");
                assertThat(pending.failureCount()).isBetween(1, 4);
            });
            var pending = outbox(expected);
            assertOriginal(before, pending);
            assertThat(records(expected)).isEmpty();
            evidence.put("outageMillis", Duration.ofNanos(System.nanoTime() - disabledAt).toMillis());
            evidence.put("duringOutage", pending);
            evidence.put("routesDuringOutage", environment.kafkaFaultProxy().description());

            environment.kafkaFaultProxy().setEnabled(Channel.PRODUCER, true);
            var published = awaitPublished(expected);
            assertOriginal(before, published);
            assertThat(published.failureCount()).isGreaterThanOrEqualTo(pending.failureCount());
            var recoveredRecords = records(expected);
            assertRequests(before, recoveredRecords);
            awaitCreationCommitted();
            evidence.put("afterRecovery", published);
            evidence.put("creationRecords", records(expected));
            evidence.put("completed", true);
        } finally {
            environment.kafkaFaultProxy().setEnabled(Channel.PRODUCER, true);
        }
    }

    private void awaitCreationCommitted() {
        await().atMost(Duration.ofSeconds(30)).ignoreExceptions().untilAsserted(() -> {
            var progress = kafka.progress(environment.creationGroup(), environment.creationTopic());
            assertThat(progress.partitions()).allSatisfy(
                partition -> assertThat(partition.committedOffset() == null ? 0L : partition.committedOffset())
                    .isEqualTo(partition.endOffset()));
        });
    }

    private void restartRelay(boolean enabled) throws Exception {
        environment.first().restart(Map.of("OUTBOX_RELAY_ENABLED", Boolean.toString(enabled)));
        first = environment.first().address();
        environment.assertGroupsReady();
    }

    private StoredOutbox outbox(Expected expected) {
        var rows = database.outboxes(expected.sourceEventId(), expected.recipientUserId(), expected.type());
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    private StoredOutbox awaitPublished(Expected expected) {
        var result = new AtomicReference<StoredOutbox>();
        await().atMost(Duration.ofSeconds(75)).untilAsserted(() -> {
            var current = outbox(expected);
            assertThat(current.status()).isEqualTo("PUBLISHED");
            assertThat(current.processingToken()).isNull();
            assertThat(current.processingUntil()).isNull();
            result.set(current);
        });
        return result.get();
    }

    private List<ObservedRecord> records(Expected expected) throws Exception {
        return recordsForRecipient(expected.recipientUserId()).stream()
            .filter(record -> decode(record).sourceEventId().equals(expected.sourceEventId())).toList();
    }

    private List<ObservedRecord> recordsForRecipient(long recipient) throws Exception {
        return kafka.snapshot(environment.creationTopic()).records().stream()
            .filter(record -> record.key() != null && record.key() == recipient).toList();
    }

    private NotificationCreateRequested decode(ObservedRecord record) {
        return JSON.readValue(record.value(), NotificationCreateRequested.class);
    }

    private void assertRequests(StoredOutbox original, List<ObservedRecord> records, int count) {
        assertThat(records).hasSize(count);
        assertRequests(original, records);
    }

    private void assertRequests(StoredOutbox original, List<ObservedRecord> records) {
        assertThat(records).isNotEmpty();
        var request = JSON.readValue(original.payload(), NotificationCreateRequested.class);
        for (var record : records) {
            assertThat(record.key()).isEqualTo(original.recipientUserId());
            assertThat(decode(record)).isEqualTo(request);
            assertThat(record.headers()).noneMatch(header -> header.key().equals("__TypeId__"));
        }
    }

    private void assertOriginal(StoredOutbox before, StoredOutbox after) {
        assertThat(after.id()).isEqualTo(before.id());
        assertThat(after.sourceEventId()).isEqualTo(before.sourceEventId());
        assertThat(after.recipientUserId()).isEqualTo(before.recipientUserId());
        assertThat(after.type()).isEqualTo(before.type());
        assertThat(after.payload()).isEqualTo(before.payload());
        assertThat(after.createdAt()).isEqualTo(before.createdAt());
    }

    private void assertPendingUnclaimed(StoredOutbox row) {
        assertThat(row.status()).isEqualTo("PENDING");
        assertThat(row.failureCount()).isZero();
        assertThat(row.processingToken()).isNull();
        assertThat(row.processingUntil()).isNull();
        assertThat(row.publishedAt()).isNull();
    }

    private LocalDateTime dbNow() {
        return jdbc.queryForObject("SELECT UTC_TIMESTAMP(6)", LocalDateTime.class);
    }
}

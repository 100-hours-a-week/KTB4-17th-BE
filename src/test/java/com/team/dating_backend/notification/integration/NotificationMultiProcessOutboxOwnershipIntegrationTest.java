package com.team.dating_backend.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import com.team.dating_backend.notification.support.NotificationBackendProcess;
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

@Tag("notification-multiprocess")
class NotificationMultiProcessOutboxOwnershipIntegrationTest extends NotificationMultiProcessTestSupport {

    private static final long CLAIM_DURATION_MS = 15_000L;
    private static final long A_RESULT_TIMEOUT_MS = 30_000L;
    private static final long B_RESULT_TIMEOUT_MS = 30_000L;
    private static final long PRODUCER_MAX_BLOCK_MS = 60_000L;
    private NotificationDatabaseProbe database;
    private NotificationKafkaProbe kafka;
    private Map<String, Object> evidence;
    private String scenario;

    @Override
    protected NotificationIntegrationEnvironment createEnvironment() {
        return new NotificationIntegrationEnvironment(Mode.RECOVERY, Map.of(
            "OUTBOX_RELAY_ENABLED", "false",
            "OUTBOX_FIXED_DELAY_MS", "100",
            "OUTBOX_CLAIM_DURATION_MS", Long.toString(CLAIM_DURATION_MS),
            "PRODUCER_MAX_BLOCK_MS", Long.toString(PRODUCER_MAX_BLOCK_MS),
            "PRODUCER_DELIVERY_TIMEOUT_MS", "120000",
            "SECOND_PRODUCER_USES_PROXY", "true"));
    }

    @BeforeEach
    void prepareScenario(TestInfo test) throws Exception {
        database = environment.databaseProbe();
        kafka = environment.kafkaProbe();
        evidence = new LinkedHashMap<>();
        scenario = "outbox-ownership-" + UUID.randomUUID();
        evidence.put("testMethod", test.getTestMethod().orElseThrow().getName());
        environment.kafkaFaultProxy().setEnabled(Channel.PRODUCER, true);
        environment.kafkaFaultProxy().setEnabled(Channel.CONSUMER, true);
        restartProcess(environment.first(), false, A_RESULT_TIMEOUT_MS, false);
        restartProcess(environment.second(), false, B_RESULT_TIMEOUT_MS, false);
        environment.assertGroupsReady();
        awaitCreationCommitted();
        evidence.put("startedAt", Instant.now());
        evidence.put("initialProcesses", List.of(environment.first().history(), environment.second().history()));
        evidence.put("claimDurationMs", CLAIM_DURATION_MS);
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

    @Test
    void 두_JVM이_같은_Outbox를_조회해도_유효한_담당이_있으면_중복_발행하지_않는다() throws Exception {
        var expected = likeNotification(first, activeUser(), activeUser());
        var original = outbox(expected);
        assertPendingUnclaimed(original);
        environment.kafkaFaultProxy().setEnabled(Channel.PRODUCER, false);

        restartProcess(environment.first(), true, B_RESULT_TIMEOUT_MS, 60_000L, PRODUCER_MAX_BLOCK_MS, true);
        var ownerA = awaitOwner(expected, null);
        evidence.put("ownerA", ownerA);
        evidence.put("ownerAPid", environment.first().pid());

        restartProcess(environment.second(), true, B_RESULT_TIMEOUT_MS, 60_000L, PRODUCER_MAX_BLOCK_MS, true);
        await().during(Duration.ofMillis(750)).atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            var current = outbox(expected);
            assertThat(current.status()).isEqualTo("PENDING");
            assertThat(current.processingToken()).isEqualTo(ownerA.processingToken());
            assertThat(current.failureCount()).isZero();
            assertThat(records(expected)).isEmpty();
        });
        evidence.put("secondServerPid", environment.second().pid());
        evidence.put("stillOwnedByA", outbox(expected));

        environment.kafkaFaultProxy().setEnabled(Channel.PRODUCER, true);
        var published = awaitPublished(expected);
        assertOriginal(original, published);
        assertThat(published.failureCount()).isZero();
        assertRequests(original, records(expected), 1);
        evidence.put("published", published);
        evidence.put("creationRecords", records(expected));
        evidence.put("completed", true);
    }

    @Test
    void 담당_서버가_종료되면_다른_JVM이_만료_후_새_토큰으로_요청을_발행한다() throws Exception {
        var expected = likeNotification(first, activeUser(), activeUser());
        var original = outbox(expected);
        assertPendingUnclaimed(original);
        environment.kafkaFaultProxy().setEnabled(Channel.PRODUCER, false);

        restartProcess(environment.first(), true, B_RESULT_TIMEOUT_MS, CLAIM_DURATION_MS, PRODUCER_MAX_BLOCK_MS, true);
        var ownerA = awaitOwner(expected, null);
        long deadPid = environment.first().pid();
        environment.first().kill();
        assertThat(environment.first().isAlive()).isFalse();
        assertThat(records(expected)).isEmpty();
        evidence.put("deadOwner", ownerA);
        evidence.put("deadPid", deadPid);
        evidence.put("outboxAfterDeath", outbox(expected));

        restartProcess(environment.second(), true, B_RESULT_TIMEOUT_MS, CLAIM_DURATION_MS, PRODUCER_MAX_BLOCK_MS,
            false);
        var ownerB = awaitOwner(expected, ownerA.processingToken());
        assertThat(ownerB.processingUntil()).isAfter(ownerA.processingUntil());
        evidence.put("replacementOwner", ownerB);
        evidence.put("replacementPid", environment.second().pid());

        environment.kafkaFaultProxy().setEnabled(Channel.PRODUCER, true);
        var published = awaitPublished(expected);
        assertOriginal(original, published);
        assertThat(published.publishedAt()).isAfter(ownerA.processingUntil());
        assertThat(published.failureCount()).isZero();
        assertRequests(original, records(expected), 1);
        evidence.put("published", published);
        evidence.put("creationRecords", records(expected));
        evidence.put("completed", true);
    }

    @Test
    void 이전_JVM의_발행_중단과_담당_해제가_새_담당의_상태를_변경하지_않는다() throws Exception {
        environment.kafkaFaultProxy().setEnabled(Channel.PRODUCER, false);
        restartProcess(environment.second(), true, B_RESULT_TIMEOUT_MS, CLAIM_DURATION_MS, PRODUCER_MAX_BLOCK_MS, true);
        var expected = likeNotification(first, activeUser(), activeUser());
        var original = outbox(expected);
        var ownerB = awaitOwner(expected, null);
        restartProcess(environment.first(), true, A_RESULT_TIMEOUT_MS, 60_000L, PRODUCER_MAX_BLOCK_MS, true);
        var ownerA = awaitOwner(expected, ownerB.processingToken());
        evidence.put("ownerB", ownerB);
        evidence.put("replacementOwnerA", ownerA);

        environment.second().requestGracefulShutdown();
        assertThat(environment.second().awaitExit(Duration.ofSeconds(30))).isTrue();
        awaitErrorLog(environment.second(), "errorCode=PUBLISH_INTERRUPTED", Duration.ofSeconds(5));
        var afterShutdown = outbox(expected);
        assertThat(afterShutdown.status()).isEqualTo("PENDING");
        assertThat(afterShutdown.processingToken()).isEqualTo(ownerA.processingToken());
        assertThat(afterShutdown.processingUntil()).isAfter(dbNow());
        assertThat(afterShutdown.failureCount()).isZero();
        assertThat(afterShutdown.lastFailureType()).isNull();
        assertThat(records(expected)).isEmpty();
        evidence.put("afterOldOwnerShutdown", afterShutdown);
        evidence.put("interruptedOwnerLog", environment.second().log().toString());

        environment.kafkaFaultProxy().setEnabled(Channel.PRODUCER, true);
        var published = awaitPublished(expected);
        assertOriginal(original, published);
        assertThat(published.failureCount()).isZero();
        assertRequests(original, records(expected), 1);
        evidence.put("published", published);
        evidence.put("creationRecords", records(expected));
        evidence.put("completed", true);
    }

    @Test
    void 만료된_이전_담당의_실패_기록과_해제가_새_담당을_변경하지_않는다() throws Exception {
        environment.kafkaFaultProxy().setEnabled(Channel.PRODUCER, false);

        restartProcess(environment.second(), true, B_RESULT_TIMEOUT_MS, CLAIM_DURATION_MS, 30_000L, true);
        var expected = likeNotification(first, activeUser(), activeUser());
        var original = outbox(expected);
        var ownerB = awaitOwner(expected, null);
        restartProcess(environment.first(), true, A_RESULT_TIMEOUT_MS, 60_000L, PRODUCER_MAX_BLOCK_MS, true);
        var ownerA = awaitOwner(expected, ownerB.processingToken());
        evidence.put("ownerA", ownerA);
        evidence.put("ownerB", ownerB);

        awaitErrorLog(environment.second(), "errorCode=OUTBOX_CLAIM_LOST", Duration.ofSeconds(40));
        var current = outbox(expected);
        assertThat(current.status()).isEqualTo("PENDING");
        assertThat(current.processingToken()).isEqualTo(ownerA.processingToken());
        assertThat(current.processingUntil()).isAfter(dbNow());
        assertThat(current.failureCount()).isZero();
        assertThat(current.lastFailureType()).isNull();
        assertThat(records(expected)).isEmpty();
        evidence.put("afterStaleFailureAndRelease", outbox(expected));
        evidence.put("oldOwnerLog", environment.second().log().toString());

        environment.kafkaFaultProxy().setEnabled(Channel.PRODUCER, true);
        var published = awaitPublished(expected);
        assertOriginal(original, published);
        assertThat(published.failureCount()).isZero();
        var publishedRecords = records(expected);
        assertThat(publishedRecords).hasSize(1);
        assertRequests(original, publishedRecords);
        evidence.put("published", published);
        evidence.put("creationRecords", publishedRecords);
        evidence.put("completed", true);
    }

    @Test
    void 만료된_이전_담당의_늦은_성공_기록은_새_담당의_성공_상태를_덮지_못한다() throws Exception {
        var expected = likeNotification(first, activeUser(), activeUser());
        var original = outbox(expected);
        environment.kafkaFaultProxy().setEnabled(Channel.PRODUCER, false);

        restartProcess(environment.first(), true, B_RESULT_TIMEOUT_MS, CLAIM_DURATION_MS, PRODUCER_MAX_BLOCK_MS, true);
        var ownerA = awaitOwner(expected, null);
        restartProcess(environment.second(), true, B_RESULT_TIMEOUT_MS, CLAIM_DURATION_MS, PRODUCER_MAX_BLOCK_MS, true);
        var ownerB = awaitOwner(expected, ownerA.processingToken());
        evidence.put("ownerA", ownerA);
        evidence.put("ownerB", ownerB);

        StoredOutbox beforeResults;
        try (var resultGate = database.lockOutbox(original.id())) {
            environment.kafkaFaultProxy().setEnabled(Channel.PRODUCER, true);
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(records(expected)).hasSize(2));
            var waiters = resultGate.awaitWaitingTransactions(2);
            evidence.put("resultWaits", waiters);
            var firstDump = environment.first().dumpThreads("outbox-stale-success");
            var secondDump = environment.second().dumpThreads("outbox-current-success");
            assertThat(Files.readString(firstDump))
                .contains("com.team.dating_backend.notification.service.NotificationOutboxClaimService.markPublished");
            assertThat(Files.readString(secondDump))
                .contains("com.team.dating_backend.notification.service.NotificationOutboxClaimService.markPublished");
            evidence.put("firstThreadDump", firstDump.toString());
            evidence.put("secondThreadDump", secondDump.toString());
            beforeResults = outbox(expected);
            assertThat(beforeResults.status()).isEqualTo("PENDING");
            assertThat(beforeResults.processingToken()).isEqualTo(ownerB.processingToken());
            assertThat(beforeResults.failureCount()).isZero();
            evidence.put("beforeResultWrites", beforeResults);
        }

        awaitErrorLog(environment.first(), "errorCode=OUTBOX_CLAIM_LOST", Duration.ofSeconds(10));
        var published = awaitPublished(expected);
        assertOriginal(original, published);
        assertThat(published.failureCount()).isZero();
        assertThat(published.publishedAt()).isNotNull();
        assertRequests(original, records(expected), 2);
        evidence.put("published", published);
        evidence.put("creationRecords", records(expected));
        evidence.put("oldOwnerLog", environment.first().log().toString());
        evidence.put("completed", true);
    }

    private void restartProcess(NotificationBackendProcess backend, boolean relayEnabled, long resultTimeoutMs,
        boolean awaitGroups) throws Exception {
        restartProcess(backend, relayEnabled, resultTimeoutMs, CLAIM_DURATION_MS, PRODUCER_MAX_BLOCK_MS, awaitGroups);
    }

    private void restartProcess(NotificationBackendProcess backend, boolean relayEnabled, long resultTimeoutMs,
        long claimDurationMs, long producerMaxBlockMs, boolean awaitGroups) throws Exception {
        if (backend.isAlive()) {
            backend.requestGracefulShutdown();
            if (!backend.awaitExit(Duration.ofSeconds(20))) {
                backend.kill();
            }
        }
        backend.restart(Map.of(
            "OUTBOX_RELAY_ENABLED", Boolean.toString(relayEnabled),
            "PUBLISH_RESULT_TIMEOUT_MS", Long.toString(resultTimeoutMs),
            "OUTBOX_CLAIM_DURATION_MS", Long.toString(claimDurationMs),
            "PRODUCER_MAX_BLOCK_MS", Long.toString(producerMaxBlockMs)));
        if (backend == environment.first()) {
            first = backend.address();
        } else {
            second = backend.address();
        }
        if (awaitGroups) {
            environment.assertGroupsReady();
        }
    }

    private StoredOutbox awaitOwner(Expected expected, String previousToken) {
        var result = new AtomicReference<StoredOutbox>();
        await().atMost(Duration.ofSeconds(25)).pollInterval(Duration.ofMillis(50)).untilAsserted(() -> {
            var current = outbox(expected);
            assertThat(current.status()).isEqualTo("PENDING");
            assertThat(current.processingToken()).isNotNull().isNotBlank();
            assertThat(current.processingUntil()).isAfter(dbNow());
            if (previousToken != null) {
                assertThat(current.processingToken()).isNotEqualTo(previousToken);
            }
            result.set(current);
        });
        return result.get();
    }

    private void awaitErrorLog(NotificationBackendProcess backend, String message, Duration timeout) {
        await().atMost(timeout).pollInterval(Duration.ofMillis(100)).untilAsserted(
            () -> assertThat(Files.readString(backend.log())).contains(message));
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
        return kafka.snapshot(environment.creationTopic()).records().stream()
            .filter(record -> record.key() != null && record.key() == expected.recipientUserId())
            .filter(record -> decode(record).sourceEventId().equals(expected.sourceEventId())).toList();
    }

    private NotificationCreateRequested decode(ObservedRecord record) {
        return JSON.readValue(record.value(), NotificationCreateRequested.class);
    }

    private void assertRequests(StoredOutbox original, List<ObservedRecord> records, int count) throws Exception {
        assertThat(records).hasSize(count);
        assertRequests(original, records);
    }

    private void assertRequests(StoredOutbox original, List<ObservedRecord> records) throws Exception {
        assertThat(records).isNotEmpty();
        var expected = JSON.readValue(original.payload(), NotificationCreateRequested.class);
        for (var record : records) {
            assertThat(record.key()).isEqualTo(original.recipientUserId());
            assertThat(decode(record)).isEqualTo(expected);
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

    private void awaitCreationCommitted() {
        await().atMost(Duration.ofSeconds(30)).ignoreExceptions().untilAsserted(() -> {
            var progress = kafka.progress(environment.creationGroup(), environment.creationTopic());
            assertThat(progress.partitions()).allSatisfy(
                partition -> assertThat(partition.committedOffset() == null ? 0L : partition.committedOffset())
                    .isEqualTo(partition.endOffset()));
        });
    }

    private LocalDateTime dbNow() {
        return jdbc.queryForObject("SELECT UTC_TIMESTAMP(6)", LocalDateTime.class);
    }
}

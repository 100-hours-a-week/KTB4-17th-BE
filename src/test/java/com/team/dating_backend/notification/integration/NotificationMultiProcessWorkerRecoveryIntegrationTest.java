package com.team.dating_backend.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import com.team.dating_backend.notification.dto.event.NotificationDeliveryEvent;
import com.team.dating_backend.notification.enums.NotificationType;
import com.team.dating_backend.notification.support.NotificationBackendProcess;
import com.team.dating_backend.notification.support.NotificationDatabaseProbe;
import com.team.dating_backend.notification.support.NotificationDatabaseProbe.StoredNotification;
import com.team.dating_backend.notification.support.NotificationDatabaseProbe.StoredOutbox;
import com.team.dating_backend.notification.support.NotificationIntegrationEnvironment;
import com.team.dating_backend.notification.support.NotificationIntegrationEnvironment.Mode;
import com.team.dating_backend.notification.support.NotificationKafkaFaultProxy.Channel;
import com.team.dating_backend.notification.support.NotificationKafkaProbe;
import com.team.dating_backend.notification.support.NotificationMultiProcessTestSupport;
import com.team.dating_backend.notification.support.NotificationSseProbe;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("notification-multiprocess")
@Timeout(120)
class NotificationMultiProcessWorkerRecoveryIntegrationTest extends NotificationMultiProcessTestSupport {

    private static final Map<String, String> WORKER_SETTINGS = Map.of(
        "OUTBOX_WORKER_ENABLED", "true", "DELIVERY_RESULT_TIMEOUT_MS", "90000",
        "PRODUCER_MAX_BLOCK_MS", "60000", "PRODUCER_DELIVERY_TIMEOUT_MS", "60000");
    private NotificationDatabaseProbe database;
    private NotificationKafkaProbe kafka;
    private Map<String, Object> evidence;
    private String reportName;
    private boolean secondWorker;
    private boolean fastProducer;

    @Override
    protected NotificationIntegrationEnvironment createEnvironment() {
        return NotificationIntegrationEnvironment.worker(Mode.RECOVERY);
    }

    @BeforeEach
    void prepareRecovery(TestInfo test) throws Exception {
        database = environment.databaseProbe();
        kafka = environment.kafkaProbe();
        evidence = new LinkedHashMap<>();
        evidence.put("scenario", test.getDisplayName());
        evidence.put("completed", false);
        reportName = "worker-recovery-" + UUID.randomUUID();
        environment.kafkaFaultProxy().setEnabled(Channel.PRODUCER, true);
        if (secondWorker) {
            restart(environment.second(), Map.of("OUTBOX_WORKER_ENABLED", "false"));
            secondWorker = false;
        }
        if (fastProducer || !environment.first().isAlive()
            || environment.first().history().getLast()
                .termination() == NotificationBackendProcess.Termination.SHUTDOWN_REQUESTED) {
            restart(environment.first(), WORKER_SETTINGS);
            fastProducer = false;
        }
        first = environment.first().address();
        second = environment.second().address();
        environment.assertGroupsReady();
        warmup();
    }

    @AfterEach
    void restoreAndReport() throws Exception {
        try {
            environment.kafkaFaultProxy().setEnabled(Channel.PRODUCER, true);
        } finally {
            evidence.put("firstHistory", environment.first().history());
            evidence.put("secondHistory", environment.second().history());
            environment.report(reportName, evidence);
        }
    }

    @Test
    void 저장_전_강제종료한_Worker의_점유를_다른_JVM이_만료_후_회수한다() throws Exception {
        var request = notification(activeUser());
        StoredOutbox claimed;
        long killedPid = environment.first().pid();
        try (var lock = database.lockRecipient(request.recipientUserId())) {
            append(request);
            evidence.put("lockWaits", lock.awaitWaitingTransactions(1));
            claimed = outbox(request);
            assertClaimed(claimed);
            assertThat(database.notifications(request)).isEmpty();
            assertThat(deliveries(request)).isEmpty();
            environment.killFirst();
            assertThat(outbox(request)).isEqualTo(claimed);
        }

        secondWorker = true;
        restart(environment.second(), Map.of("OUTBOX_WORKER_ENABLED", "true"));
        second = environment.second().address();
        assertThat(environment.second().pid()).isNotEqualTo(killedPid);
        assertThat(environment.first().isAlive()).isFalse();
        assertThat(jdbc.queryForObject("SELECT UTC_TIMESTAMP(6) < ?", Boolean.class, claimed.processingUntil()))
            .isTrue();
        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            assertThat(outbox(request)).isEqualTo(claimed);
            assertThat(database.notifications(request)).isEmpty();
        });
        var connection = subscribe(second, request.recipientUserId());

        var completed = awaitCompleted(request);
        assertOriginal(claimed, completed);
        assertThat(completed.completedAt()).isAfterOrEqualTo(claimed.processingUntil());
        assertThat(completed.failureCount()).isZero();
        assertDelivery(request, awaitStored(request), connection, 1);
        checkpoint(request, completed);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 저장_후_종료해도_기존_알림_ID와_읽음_삭제_상태를_보존한다(boolean deleted) throws Exception {
        var request = notification(activeUser());
        var connection = subscribe(second, request.recipientUserId());
        environment.kafkaFaultProxy().setEnabled(Channel.PRODUCER, false);
        append(request);
        var before = awaitStored(request);
        var claimed = outbox(request);
        assertClaimed(claimed);
        assertThat(deliveries(request)).isEmpty();
        environment.killFirst();
        assertThat(request(second, "PUT", NOTIFICATIONS + "/" + before.id() + "/read-receipt",
            token(request.recipientUserId()), null).statusCode()).isEqualTo(204);
        if (deleted) {
            assertThat(request(second, "DELETE", NOTIFICATIONS + "/" + before.id(),
                token(request.recipientUserId()), null).statusCode()).isEqualTo(204);
        }
        var expected = database.notifications(request).getFirst();
        assertThat(expected.readAt()).isNotNull();
        assertThat(expected.deletedAt() != null).isEqualTo(deleted);
        assertThat(expected).usingRecursiveComparison().ignoringFields("readAt", "deletedAt").isEqualTo(before);
        environment.kafkaFaultProxy().setEnabled(Channel.PRODUCER, true);

        restartFirst();
        var completed = awaitCompleted(request);
        assertOriginal(claimed, completed);
        assertThat(completed.failureCount()).isZero();
        assertThat(database.notifications(request)).containsExactly(expected);
        assertDelivery(request, expected, connection, deleted ? 0 : 1);
        checkpoint(request, completed);
    }

    @Test
    void Delivery_성공_후_완료_전_종료하면_재발행하되_같은_SSE_연결에는_한번만_보낸다() throws Exception {
        var request = notification(activeUser());
        var connection = subscribe(second, request.recipientUserId());
        environment.kafkaFaultProxy().setEnabled(Channel.PRODUCER, false);
        append(request);
        var before = awaitStored(request);
        var claimed = outbox(request);
        assertClaimed(claimed);
        try (var lock = database.lockOutbox(claimed.id())) {
            environment.kafkaFaultProxy().setEnabled(Channel.PRODUCER, true);
            assertDelivery(request, before, connection, 1);
            evidence.put("completionLockWaits", lock.awaitWaitingTransactions(1));
            assertThat(outbox(request)).isEqualTo(claimed);
            environment.killFirst();
        }
        assertThat(request(second, "PUT", NOTIFICATIONS + "/" + before.id() + "/read-receipt",
            token(request.recipientUserId()), null).statusCode()).isEqualTo(204);
        var expected = database.notifications(request).getFirst();
        assertThat(expected.readAt()).isNotNull();

        restartFirst();
        var completed = awaitCompleted(request);
        assertOriginal(claimed, completed);
        assertThat(completed.failureCount()).isZero();
        assertThat(database.notifications(request)).containsExactly(expected);
        assertDelivery(request, before, connection, 2);
        assertThat(deliveries(request)).filteredOn(event -> event.notification().readAt() != null).hasSize(1);
        checkpoint(request, completed);
    }

    @Test
    void SIGTERM은_발행_대기를_중단하고_실패_횟수_증가_없이_점유를_해제한다() throws Exception {
        var request = notification(activeUser());
        var connection = subscribe(second, request.recipientUserId());
        environment.kafkaFaultProxy().setEnabled(Channel.PRODUCER, false);
        append(request);
        var stored = awaitStored(request);
        var claimed = outbox(request);
        assertClaimed(claimed);

        var backend = environment.first();
        backend.requestGracefulShutdown();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(outbox(request).processingToken()).isNull();
            assertThat(outbox(request).processingUntil()).isNull();
        });
        var released = outbox(request);
        assertThat(released.status()).isEqualTo("PENDING");
        assertThat(released.processingToken()).isNull();
        assertThat(released.processingUntil()).isNull();
        assertThat(released.failureCount()).isZero();
        assertThat(released.lastFailureType()).isNull();
        environment.kafkaFaultProxy().setEnabled(Channel.PRODUCER, true);
        assertThat(backend.awaitExit(Duration.ofSeconds(20))).isTrue();

        restartFirst();
        var completed = awaitCompleted(request);
        assertOriginal(claimed, completed);
        assertThat(completed.completedAt()).isBefore(claimed.processingUntil());
        assertThat(completed.failureCount()).isZero();
        assertDelivery(request, stored, connection, null);
        evidence.put("released", released);
        checkpoint(request, completed);
    }

    @Test
    void 일시적인_Delivery_실패는_같은_Outbox와_알림을_사용해_재시도한다() throws Exception {
        useFastProducer();
        var request = notification(activeUser());
        var connection = subscribe(second, request.recipientUserId());
        environment.kafkaFaultProxy().setEnabled(Channel.PRODUCER, false);
        append(request);
        var stored = awaitStored(request);
        var failed = await().atMost(Duration.ofSeconds(10)).until(() -> outbox(request),
            row -> row.failureCount() == 1 && row.processingToken() == null);
        assertThat(failed.status()).isEqualTo("PENDING");
        assertThat(failed.lastFailureType()).isEqualTo("OUTBOX_DELIVERY_FAILED");
        assertThat(jdbc.queryForObject(
            "SELECT next_attempt_at > UTC_TIMESTAMP(6) FROM notification_request_outbox WHERE id = ?",
            Boolean.class, failed.id())).isTrue();
        environment.kafkaFaultProxy().setEnabled(Channel.PRODUCER, true);

        var completed = awaitCompleted(request);
        assertOriginal(failed, completed);
        assertThat(completed.failureCount()).isEqualTo(1);
        assertThat(completed.lastFailureType()).isEqualTo(failed.lastFailureType());
        assertThat(database.notifications(request)).containsExactly(stored);
        assertDelivery(request, stored, connection, null);
        checkpoint(request, completed);
    }

    @Test
    void FAILED_재처리는_기존_Outbox와_사건_ID_최초_요청_시각_실패_이력을_유지한다() throws Exception {
        useFastProducer();
        var request = notification(activeUser());
        var connection = subscribe(second, request.recipientUserId());
        environment.kafkaFaultProxy().setEnabled(Channel.PRODUCER, false);
        append(request);
        var stored = awaitStored(request);
        var failed = await().atMost(Duration.ofSeconds(45)).until(() -> outbox(request),
            row -> row.status().equals("FAILED"));
        assertThat(failed.failureCount()).isEqualTo(5);
        assertThat(failed.lastFailureType()).isEqualTo("OUTBOX_DELIVERY_FAILED");
        assertThat(failed.processingToken()).isNull();
        assertThat(failed.processingUntil()).isNull();
        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(2))
            .untilAsserted(() -> assertThat(outbox(request)).isEqualTo(failed));
        assertThat(connection.notifications()).isEmpty();
        environment.kafkaFaultProxy().setEnabled(Channel.PRODUCER, true);
        var probe = notification(activeUser());
        append(probe);
        awaitCompleted(probe);

        assertThat(jdbc.update("""
            UPDATE notification_request_outbox SET status = 'PENDING', next_attempt_at = UTC_TIMESTAMP(6)
            WHERE id = ? AND status = 'FAILED' AND processing_token IS NULL AND processing_until IS NULL
            """, failed.id())).isEqualTo(1);

        var completed = awaitCompleted(request);
        assertOriginal(failed, completed);
        assertThat(completed.failureCount()).isEqualTo(failed.failureCount());
        assertThat(completed.lastFailureType()).isEqualTo(failed.lastFailureType());
        assertThat(database.notifications(request)).containsExactly(stored);
        assertDelivery(request, stored, connection, null);
        evidence.put("failed", failed);
        checkpoint(request, completed);
    }

    private void useFastProducer() throws Exception {
        fastProducer = true;
        restart(environment.first(), Map.of("DELIVERY_RESULT_TIMEOUT_MS", "1000",
            "PRODUCER_MAX_BLOCK_MS", "1000", "PRODUCER_DELIVERY_TIMEOUT_MS", "4000"));
        first = environment.first().address();
        environment.assertGroupsReady();
        warmup();
    }

    private void restart(NotificationBackendProcess backend, Map<String, String> settings) throws Exception {
        if (backend.isAlive()) {
            backend.requestGracefulShutdown();
            assertThat(backend.awaitExit(Duration.ofSeconds(20))).isTrue();
        }
        backend.restart(settings);
    }

    private void warmup() {
        var request = notification(activeUser());
        append(request);
        awaitCompleted(request);
        environment.awaitProcessed();
    }

    private NotificationCreateRequested notification(long userId) {
        return new NotificationCreateRequested("WORKER_RECOVERY:" + UUID.randomUUID(), userId, NotificationType.NOTICE,
            "Worker 복구 알림", "Worker 복구 본문", null, null, Instant.now().truncatedTo(ChronoUnit.MICROS));
    }

    private void append(NotificationCreateRequested request) {
        jdbc.update("""
            INSERT INTO notification_request_outbox (source_event_id, recipient_user_id, type, payload)
            VALUES (?, ?, ?, ?)
            """, request.sourceEventId(), request.recipientUserId(), request.type().name(),
            JSON.writeValueAsString(request));
    }

    private StoredOutbox outbox(NotificationCreateRequested request) {
        var rows = database.outboxes(request.sourceEventId(), request.recipientUserId(), request.type().name());
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    private StoredOutbox awaitCompleted(NotificationCreateRequested request) {
        var completed = await().atMost(Duration.ofSeconds(45)).until(() -> outbox(request),
            row -> row.status().equals("COMPLETED"));
        assertThat(completed.completedAt()).isNotNull();
        assertThat(completed.processingToken()).isNull();
        assertThat(completed.processingUntil()).isNull();
        return completed;
    }

    private StoredNotification awaitStored(NotificationCreateRequested request) {
        return await().atMost(Duration.ofSeconds(10))
            .until(() -> database.notifications(request), rows -> rows.size() == 1)
            .getFirst();
    }

    private void assertClaimed(StoredOutbox row) {
        assertThat(row.status()).isEqualTo("PENDING");
        assertThat(row.processingToken()).isNotBlank();
        assertThat(row.processingUntil()).isNotNull();
        assertThat(row.completedAt()).isNull();
        evidence.put("claimed", row);
    }

    private void assertOriginal(StoredOutbox before, StoredOutbox after) {
        assertThat(after).usingRecursiveComparison().comparingOnlyFields("id", "sourceEventId", "recipientUserId",
            "type", "payload", "createdAt").isEqualTo(before);
    }

    private List<NotificationDeliveryEvent> deliveries(NotificationCreateRequested request) throws Exception {
        return kafka.snapshot(environment.deliveryTopic()).records().stream()
            .map(record -> JSON.readValue(record.value(), NotificationDeliveryEvent.class))
            .filter(event -> event.sourceEventId().equals(request.sourceEventId())
                && event.recipientUserId().equals(request.recipientUserId()))
            .toList();
    }

    private void assertDelivery(NotificationCreateRequested request, StoredNotification stored,
        NotificationSseProbe connection, Integer expectedRecords) {
        if (outbox(request).status().equals("COMPLETED")) {
            environment.awaitProcessed();
        }
        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            var events = deliveries(request);
            if (expectedRecords != null) {
                assertThat(events).hasSize(expectedRecords);
            } else {
                assertThat(events).isNotEmpty();
            }
            assertThat(events)
                .allSatisfy(event -> assertThat(event.notification().notificationId()).isEqualTo(stored.id()));
            connection.assertHealthy();
            assertThat(connection.notifications()).hasSize(expectedRecords != null && expectedRecords == 0 ? 0 : 1);
            assertThat(connection.notifications()).allSatisfy(event -> {
                assertThat(event.notificationId()).isEqualTo(stored.id());
                assertThat(event.createdAt()).isEqualTo(stored.createdAt());
                assertThat(event.readAt()).isEqualTo(stored.readAt());
            });
        });
    }

    private void checkpoint(NotificationCreateRequested request, StoredOutbox completed) throws Exception {
        environment.awaitProcessed();
        evidence.put("request", request);
        evidence.put("outbox", completed);
        evidence.put("notifications", database.notifications(request));
        evidence.put("deliveries", deliveries(request));
        evidence.put("completed", true);
    }
}

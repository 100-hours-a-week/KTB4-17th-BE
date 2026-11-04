package com.team.dating_backend.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import com.team.dating_backend.notification.dto.event.NotificationDeliveryEvent;
import com.team.dating_backend.notification.enums.NotificationType;
import com.team.dating_backend.notification.support.NotificationDatabaseProbe;
import com.team.dating_backend.notification.support.NotificationDatabaseProbe.StoredNotification;
import com.team.dating_backend.notification.support.NotificationIntegrationEnvironment;
import com.team.dating_backend.notification.support.NotificationKafkaFaultProxy.Channel;
import com.team.dating_backend.notification.support.NotificationKafkaProbe;
import com.team.dating_backend.notification.support.NotificationKafkaProbe.ObservedRecord;
import com.team.dating_backend.notification.support.NotificationKafkaProbe.Position;
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
class NotificationMultiProcessRecoveryIntegrationTest extends NotificationMultiProcessTestSupport {

    private NotificationKafkaProbe kafka;
    private NotificationDatabaseProbe database;
    private Map<String, Object> evidence;
    private String reportName;

    @Override
    protected NotificationIntegrationEnvironment createEnvironment() {
        return new NotificationIntegrationEnvironment(NotificationIntegrationEnvironment.Mode.RECOVERY);
    }

    @BeforeEach
    void prepareRecovery(TestInfo test) throws Exception {
        evidence = new LinkedHashMap<>();
        evidence.put("scenario", test.getDisplayName());
        evidence.put("completed", false);
        reportName = "process-recovery-" + UUID.randomUUID();
        if (!environment.first().isAlive()) {
            restartFirst();
        }
        kafka = environment.kafkaProbe();
        database = environment.databaseProbe();
        var warmup = notification(activeUser());
        var input = kafka.publish(warmup, 0);
        awaitInput(input);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(deliveries(warmup)).hasSize(1));
        environment.awaitProcessed();
        evidence.put("warmup",
            Map.of("request", warmup, "input", kafka.inputState(environment.creationGroup(), input)));
    }

    @AfterEach
    void restoreRoutesAndReport() throws Exception {
        Exception failure = null;
        for (var channel : Channel.values()) {
            try {
                environment.kafkaFaultProxy().setEnabled(channel, true);
            } catch (Exception exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        try {
            evidence.put("firstHistory", environment.first().history());
            evidence.put("secondHistory", environment.second().history());
            environment.report(reportName, evidence);
        } catch (Exception exception) {
            if (failure == null) {
                failure = exception;
            } else {
                failure.addSuppressed(exception);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    @Test
    void DB_commit_전_강제종료한_요청을_재시작_후_한_행으로_저장하고_전파한다() throws Exception {
        long userId = activeUser();
        var request = notification(userId);
        var connection = subscribe(second, userId);
        Position input;
        try (var lock = database.lockRecipient(userId)) {
            input = kafka.publish(request, 0);
            evidence.put("lockWaits", lock.awaitWaitingTransactions(1));
            assertThat(database.notifications(request)).isEmpty();
            assertThat(deliveries(request)).isEmpty();
            assertThat(kafka.inputState(environment.creationGroup(), input).completed()).isFalse();
            checkpoint("beforeKill", request, input, connection);
            environment.killFirst();
            assertThat(database.notifications(request)).isEmpty();
        }

        restartFirst();
        var stored = awaitStored(request);
        assertRecovered(request, input, stored, connection, 1);
        checkpoint("afterRestart", request, input, connection);
        evidence.put("completed", true);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void DB_commit_후_전파_전_종료해도_기존_ID와_읽음_삭제_상태를_보존한다(boolean deleted) throws Exception {
        long userId = activeUser();
        var request = notification(userId);
        var connection = subscribe(second, userId);
        var proxy = environment.kafkaFaultProxy();
        proxy.setEnabled(Channel.PRODUCER, false);
        var input = kafka.publish(request, 0);
        var before = awaitStored(request);
        assertThat(deliveries(request)).isEmpty();
        assertThat(kafka.inputState(environment.creationGroup(), input).completed()).isFalse();
        checkpoint("beforeKill", request, input, connection);
        environment.killFirst();

        assertThat(request(second, "PUT", NOTIFICATIONS + "/" + before.id() + "/read-receipt", token(userId), null)
            .statusCode()).isEqualTo(204);
        if (deleted) {
            assertThat(request(second, "DELETE", NOTIFICATIONS + "/" + before.id(), token(userId), null)
                .statusCode()).isEqualTo(204);
        }
        var expected = database.notifications(request).getFirst();
        assertThat(expected.readAt()).isNotNull();
        assertThat(expected.deletedAt() != null).isEqualTo(deleted);
        assertThat(expected).usingRecursiveComparison().ignoringFields("readAt", "deletedAt").isEqualTo(before);
        evidence.put("deleted", deleted);
        evidence.put("stateBeforeRestart", expected);
        proxy.setEnabled(Channel.PRODUCER, true);

        restartFirst();
        assertRecovered(request, input, expected, connection, deleted ? 0 : 1);
        checkpoint("afterRestart", request, input, connection);
        evidence.put("completed", true);
    }

    @Test
    void 전파_후_입력_commit_전_종료하면_같은_ID로_재전파하고_읽음_상태를_보존한다() throws Exception {
        long userId = activeUser();
        var request = notification(userId);
        var connection = subscribe(second, userId);
        var proxy = environment.kafkaFaultProxy();
        proxy.setEnabled(Channel.PRODUCER, false);
        var input = kafka.publish(request, 0);
        var before = awaitStored(request);
        proxy.setEnabled(Channel.CONSUMER, false);
        proxy.setEnabled(Channel.PRODUCER, true);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            connection.assertHealthy();
            assertThat(connection.notifications()).hasSize(1);
            assertThat(deliveries(request)).hasSize(1);
        });
        assertThat(connection.notifications().getFirst().notificationId()).isEqualTo(before.id());
        assertThat(connection.notifications().getFirst().readAt()).isNull();
        assertThat(kafka.inputState(environment.creationGroup(), input).completed()).isFalse();
        checkpoint("beforeKill", request, input, connection);
        environment.killFirst();

        assertThat(request(second, "PUT", NOTIFICATIONS + "/" + before.id() + "/read-receipt", token(userId), null)
            .statusCode()).isEqualTo(204);
        var expected = database.notifications(request).getFirst();
        assertThat(expected.readAt()).isNotNull();
        proxy.setEnabled(Channel.CONSUMER, true);
        restartFirst();

        assertRecovered(request, input, expected, connection, 2);
        assertThat(connection.notifications().getLast().readAt()).isEqualTo(expected.readAt());
        checkpoint("afterRestart", request, input, connection);
        evidence.put("completed", true);
    }

    private void assertRecovered(NotificationCreateRequested request, Position input, StoredNotification expected,
        NotificationSseProbe connection, int count) throws Exception {
        awaitInput(input);
        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(database.notifications(request)).containsExactly(expected);
            assertThat(deliveries(request)).hasSize(count);
            connection.assertHealthy();
            assertThat(connection.notifications()).hasSize(count).allSatisfy(notification -> {
                assertThat(notification.notificationId()).isEqualTo(expected.id());
                assertThat(notification.title()).isEqualTo(expected.title());
                assertThat(notification.body()).isEqualTo(expected.body());
                assertThat(notification.createdAt()).isEqualTo(expected.createdAt());
            });
        });
        assertThat(kafka.snapshot(environment.retryTopic()).records()).isEmpty();
        assertThat(kafka.snapshot(environment.dltTopic()).records()).isEmpty();
    }

    private void awaitInput(Position input) {
        await().atMost(Duration.ofSeconds(30))
            .untilAsserted(() -> assertThat(kafka.inputState(environment.creationGroup(), input).completed()).isTrue());
    }

    private StoredNotification awaitStored(NotificationCreateRequested request) {
        return await().atMost(Duration.ofSeconds(15))
            .until(() -> database.notifications(request), rows -> rows.size() == 1)
            .getFirst();
    }

    private List<ObservedRecord> deliveries(NotificationCreateRequested request) throws Exception {
        return kafka.snapshot(environment.deliveryTopic()).records().stream().filter(record -> {
            var event = JSON.readValue(record.value(), NotificationDeliveryEvent.class);
            return event.sourceEventId().equals(request.sourceEventId())
                && event.recipientUserId().equals(request.recipientUserId())
                && event.notification().type().equals(request.type().name());
        }).toList();
    }

    private void checkpoint(String phase, NotificationCreateRequested request, Position input,
        NotificationSseProbe connection) throws Exception {
        var state = new LinkedHashMap<String, Object>();
        state.put("request", request);
        state.put("input", kafka.inputState(environment.creationGroup(), input));
        state.put("database", database.notifications(request));
        state.put("sourceSnapshot", kafka.snapshot(environment.creationTopic()));
        state.put("deliveryRecords", deliveries(request));
        state.put("sseConnectionId", connection.connectionId());
        state.put("sseNotifications", connection.notifications());
        state.put("firstHistory", environment.first().history());
        evidence.put(phase, state);
    }

    private NotificationCreateRequested notification(long userId) {
        return new NotificationCreateRequested(1, "PR25_RECOVERY:" + UUID.randomUUID(), userId, NotificationType.NOTICE,
            "복구 알림", "복구 본문", null, null, Instant.now().truncatedTo(ChronoUnit.MICROS));
    }
}

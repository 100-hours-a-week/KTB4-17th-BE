package com.team.dating_backend.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import com.team.dating_backend.notification.dto.event.NotificationDeliveryEvent;
import com.team.dating_backend.notification.enums.NotificationType;
import com.team.dating_backend.notification.support.NotificationKafkaProbe.Position;
import com.team.dating_backend.notification.support.NotificationMultiProcessTestSupport;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Tag("notification-multiprocess")
@Timeout(90)
class NotificationMultiProcessDeduplicationIntegrationTest extends NotificationMultiProcessTestSupport {

    @Test
    void 서로_다른_Partition의_같은_사건을_두_서버가_동시에_저장해도_한_행과_같은_ID로_전파한다() throws Exception {
        long userId = activeUser();
        var request = new NotificationCreateRequested(1, "PR25_CONCURRENT:" + UUID.randomUUID(), userId,
            NotificationType.NOTICE, "동시 알림", "같은 사건", null, null,
            Instant.now().truncatedTo(ChronoUnit.MICROS));
        var connection = subscribe(second, userId);
        var database = environment.databaseProbe();
        var kafka = environment.kafkaProbe();
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("request", request);
        evidence.put("completed", false);
        Throwable failure = null;
        try {
            Position firstInput;
            Position secondInput;
            try (var lock = database.lockRecipient(userId);
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var firstPublish = executor.submit(() -> kafka.publish(request, 0));
                var secondPublish = executor.submit(() -> kafka.publish(request, 1));
                firstInput = firstPublish.get(15, TimeUnit.SECONDS);
                secondInput = secondPublish.get(15, TimeUnit.SECONDS);
                var waiting = lock.awaitWaitingTransactions(1);
                var inserts = await().atMost(Duration.ofSeconds(15)).until(() -> database.inFlightInserts(request),
                    current -> current.stream().map(insert -> insert.connectionId()).distinct().count() == 2);
                assertThat(inserts).hasSize(2).allSatisfy(insert -> {
                    assertThat(insert.transactionState()).isEqualTo("ACTIVE");
                    assertThat(insert.transactionEventId()).isNotNull();
                });
                assertThat(firstInput.topicPartition()).isNotEqualTo(secondInput.topicPartition());
                assertThat(database.notifications(request)).isEmpty();
                var firstState = kafka.inputState(environment.creationGroup(), firstInput);
                var secondState = kafka.inputState(environment.creationGroup(), secondInput);
                assertThat(firstState.completed()).isFalse();
                assertThat(secondState.completed()).isFalse();
                evidence.put("beforeRelease",
                    Map.of("waits", waiting, "inFlightInserts", inserts, "firstInput", firstState,
                        "secondInput", secondState, "sourceSnapshot", kafka.snapshot(environment.creationTopic())));
            }

            await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                assertThat(database.notifications(request)).hasSize(1);
                var stored = database.notifications(request).getFirst();
                assertThat(stored.createdAt())
                    .isEqualTo(LocalDateTime.ofInstant(request.requestedAt(), ZoneOffset.UTC));
                assertThat(kafka.inputState(environment.creationGroup(), firstInput).completed()).isTrue();
                assertThat(kafka.inputState(environment.creationGroup(), secondInput).completed()).isTrue();
                connection.assertHealthy();
                assertThat(connection.notifications()).hasSize(2).allSatisfy(notification -> {
                    assertThat(notification.notificationId()).isEqualTo(stored.id());
                    assertThat(notification.title()).isEqualTo(request.title());
                    assertThat(notification.body()).isEqualTo(request.body());
                    assertThat(notification.createdAt()).isEqualTo(stored.createdAt());
                });
                var deliveries = kafka.snapshot(environment.deliveryTopic()).records();
                assertThat(deliveries).hasSize(2).allSatisfy(record -> {
                    var event = JSON.readValue(record.value(), NotificationDeliveryEvent.class);
                    assertThat(event.sourceEventId()).isEqualTo(request.sourceEventId());
                    assertThat(event.recipientUserId()).isEqualTo(userId);
                    assertThat(event.notification().notificationId()).isEqualTo(stored.id());
                });
            });
            assertThat(kafka.snapshot(environment.dltTopic()).records()).isEmpty();
            evidence.put("afterRelease", Map.of("database", database.notifications(request),
                "deliverySnapshot", kafka.snapshot(environment.deliveryTopic()),
                "retrySnapshot", kafka.snapshot(environment.retryTopic()), "dltSnapshot",
                kafka.snapshot(environment.dltTopic()),
                "sseConnectionId", connection.connectionId(), "sseNotifications", connection.notifications(),
                "firstInput", kafka.inputState(environment.creationGroup(), firstInput),
                "secondInput", kafka.inputState(environment.creationGroup(), secondInput)));
            evidence.put("completed", true);
        } catch (Exception | Error exception) {
            failure = exception;
            throw exception;
        } finally {
            try {
                evidence.put("firstHistory", environment.first().history());
                evidence.put("secondHistory", environment.second().history());
                environment.report("concurrent-deduplication", evidence);
            } catch (Exception | Error reportFailure) {
                if (failure == null) {
                    throw reportFailure;
                }
                failure.addSuppressed(reportFailure);
            }
        }
    }
}

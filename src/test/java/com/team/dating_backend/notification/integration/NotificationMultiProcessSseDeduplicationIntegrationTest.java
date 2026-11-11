package com.team.dating_backend.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.team.dating_backend.notification.dto.event.NotificationDeliveryEvent;
import com.team.dating_backend.notification.dto.response.NotificationEventResponse;
import com.team.dating_backend.notification.support.NotificationKafkaProbe.Position;
import com.team.dating_backend.notification.support.NotificationMultiProcessTestSupport;
import com.team.dating_backend.notification.support.NotificationSseProbe;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.Timeout;

@Tag("notification-multiprocess")
@Timeout(90)
class NotificationMultiProcessSseDeduplicationIntegrationTest extends NotificationMultiProcessTestSupport {

    private final List<NotificationSseProbe> streams = new ArrayList<>();
    private Map<String, Object> evidence;
    private List<String> deliveryGroups;
    private String scenario;

    @BeforeEach
    void prepareScenario(TestInfo test) throws Exception {
        streams.clear();
        evidence = new LinkedHashMap<>();
        evidence.put("scenario", test.getDisplayName());
        evidence.put("completed", false);
        scenario = "sse-deduplication-" + UUID.randomUUID();
        deliveryGroups = environment.deliveryGroups();
        assertThat(deliveryGroups).hasSize(2).doesNotHaveDuplicates();
        assertThat(environment.first().pid()).isNotEqualTo(environment.second().pid());
        evidence.put("deliveryGroups", deliveryGroups);
    }

    @AfterEach
    void recordScenario() throws Exception {
        evidence.put("connections", streams.stream().map(stream -> Map.of(
            "server", stream.server(), "userId", stream.userId(), "connectionId", stream.connectionId(),
            "notifications", stream.notifications(), "heartbeatCount", stream.heartbeats().size())).toList());
        environment.report(scenario, evidence);
    }

    @Test
    void 서로_다른_Partition의_동시_중복_Delivery도_두_서버의_각_연결에_한_번만_전송한다() throws Exception {
        long userId = activeUser();
        var onFirst = open(first, userId);
        var anotherOnFirst = open(first, userId);
        var onSecond = open(second, userId);
        var otherUser = open(second, activeUser());
        var event = event(userId, 100L);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        Position firstPosition;
        Position secondPosition;

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                var firstPublish = executor.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    return publish(event, 0);
                });
                var secondPublish = executor.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    return publish(event, 1);
                });
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                firstPosition = firstPublish.get(15, TimeUnit.SECONDS);
                secondPosition = secondPublish.get(15, TimeUnit.SECONDS);
            } finally {
                start.countDown();
            }
        }

        assertThat(firstPosition.topicPartition()).isNotEqualTo(secondPosition.topicPartition());
        awaitConsumed(firstPosition, secondPosition);
        assertNotifications(List.of(event.notification()), onFirst, anotherOnFirst, onSecond);
        otherUser.assertHealthy();
        assertThat(otherUser.notifications()).isEmpty();
        assertThat(List.of(onFirst.connectionId(), anotherOnFirst.connectionId(), onSecond.connectionId()))
            .doesNotHaveDuplicates();
        evidence.put("publishedPositions", List.of(firstPosition, secondPosition));
        evidence.put("completed", true);
    }

    @Test
    void 재연결은_동일_ID를_다시_받고_유지된_연결은_중복을_생략한_뒤_다음_알림을_받는다() throws Exception {
        long userId = activeUser();
        var original = open(first, userId);
        var retained = open(second, userId);
        var event = event(userId, 100L);
        awaitConsumed(publish(event, 0));
        assertNotifications(List.of(event.notification()), original, retained);

        original.close();
        var reconnected = open(first, userId);
        assertThat(reconnected.connectionId()).isNotEqualTo(original.connectionId());
        var duplicate = publish(event, 0);
        var next = event(userId, 101L);
        var last = publish(next, 0);

        awaitConsumed(duplicate, last);
        assertNotifications(List.of(event.notification(), next.notification()), reconnected, retained);
        assertThat(original.notifications()).containsExactly(event.notification());
        evidence.put("publishedPositions", List.of(duplicate, last));
        evidence.put("completed", true);
    }

    @Test
    void Kafka_재전송도_연결별_최근_256개_이력을_따르고_새_연결의_이력은_독립적이다() throws Exception {
        long userId = activeUser();
        var established = open(first, userId);
        var events = new ArrayList<NotificationDeliveryEvent>();
        Position last = null;
        for (long id = 1; id <= 256; id++) {
            var event = event(userId, id);
            events.add(event);
            last = publish(event, 0);
        }
        awaitConsumed(last);
        var initial = events.stream().map(NotificationDeliveryEvent::notification).toList();
        assertNotifications(initial, established);

        var fresh = open(second, userId);
        var oldest = events.getFirst();
        awaitConsumed(publish(oldest, 0));
        assertNotifications(initial, established);
        assertNotifications(List.of(oldest.notification()), fresh);

        var overflow = event(userId, 257L);
        publish(overflow, 0);
        var replay = publish(oldest, 0);
        awaitConsumed(replay);

        var expected = new ArrayList<>(initial);
        expected.add(overflow.notification());
        expected.add(oldest.notification());
        assertNotifications(expected, established);
        assertNotifications(List.of(oldest.notification(), overflow.notification()), fresh);
        evidence.put("lastReplay", replay);
        evidence.put("completed", true);
    }

    private NotificationSseProbe open(URI server, long userId) {
        var stream = subscribe(server, userId);
        streams.add(stream);
        return stream;
    }

    private NotificationDeliveryEvent event(long userId, long notificationId) {
        return new NotificationDeliveryEvent("SSE_DEDUP:" + UUID.randomUUID(), userId,
            new NotificationEventResponse(notificationId, "NOTICE", "알림 제목", "알림 본문", null, null, null,
                LocalDateTime.of(2026, 11, 11, 1, 0)));
    }

    private Position publish(NotificationDeliveryEvent event, int partition) throws Exception {
        return environment.kafkaProbe().publishDelivery(environment.deliveryTopic(), event, partition);
    }

    private void awaitConsumed(Position... positions) {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            for (String group : deliveryGroups) {
                for (Position position : positions) {
                    assertThat(environment.kafkaProbe().inputState(group, position).completed())
                        .as("group=%s, position=%s", group, position).isTrue();
                }
            }
        });
    }

    private void assertNotifications(List<NotificationEventResponse> expected, NotificationSseProbe... connections) {
        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            for (var connection : connections) {
                connection.assertHealthy();
                assertThat(connection.notifications()).as("connection=%s", connection.connectionId())
                    .containsExactlyElementsOf(expected);
                assertThat(connection.heartbeats()).isNotEmpty();
            }
        });
    }
}

package com.team.dating_backend.notification.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.team.dating_backend.notification.config.NotificationDeliveryProperties;
import com.team.dating_backend.notification.dto.event.NotificationDeliveryEvent;
import com.team.dating_backend.notification.dto.response.NotificationEventResponse;
import com.team.dating_backend.notification.enums.NotificationSseCloseReason;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class NotificationSseObservationTest {

    @Test
    void 연결_등록과_종료는_한번만_계측하고_동적_식별자는_metric_tag로_등록하지_않는다() {
        var meters = new SimpleMeterRegistry();
        try {
            var observation = observation(meters);
            var registry = new NotificationEmitterRegistry(observation);
            var connection = connection();
            registry.register(connection);
            registry.observeRegistration(connection);
            registry.observeRegistration(connection);
            registry.remove(connection, NotificationSseCloseReason.SHUTDOWN);
            registry.remove(connection, NotificationSseCloseReason.COMPLETION);
            observation.sendFailed(connection, NotificationSseObservation.EventKind.NOTIFICATION,
                NotificationSseCloseReason.SERVER_ERROR, new IllegalStateException());
            observation.initializationFailed(connection, new IllegalStateException());
            observation.deliveryProcessed(record(1, TimestampType.CREATE_TIME), 0, 37, null);
            observation.deliveryProcessed(record(1, TimestampType.CREATE_TIME), null, 41, new IllegalStateException());

            assertThat(meters.get("notification.sse.connections.registered").counter().count()).isEqualTo(1);
            assertThat(meters.get("notification.sse.connections.closed").tag("reason", "SHUTDOWN")
                .counter().count()).isEqualTo(1);
            assertThat(meters.get("notification.sse.connection.duration").tag("reason", "SHUTDOWN")
                .timer().count()).isEqualTo(1);
            assertThat(meters.find("notification.sse.connections.closed").tag("reason", "COMPLETION").counter())
                .isNull();
            assertThat(
                meters.get("notification.sse.send.failures").tags("event", "NOTIFICATION", "reason", "SERVER_ERROR")
                    .counter().count())
                .isEqualTo(1);
            assertThat(meters.get("notification.sse.initialization.failures").counter().count()).isEqualTo(1);
            assertThat(meters.get("notification.delivery.processing").tag("result", "PROCESSED").timer().count())
                .isEqualTo(1);
            assertThat(meters.get("notification.delivery.processing").tag("result", "FAILED").timer().count())
                .isEqualTo(1);
            assertThat(meters.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags())
                .extracting(Tag::getKey).isSubsetOf("reason", "event", "result"));
        } finally {
            meters.close();
        }
    }

    @Test
    void 레코드_나이는_Kafka_timestamp로_측정하고_누락과_미래_시각을_지연값에서_제외한다() {
        var clock = new MockClock();
        clock.add(Duration.ofSeconds(5));
        var meters = new SimpleMeterRegistry(SimpleConfig.DEFAULT, clock);
        try {
            var observation = observation(meters);
            observation.deliveryReceived(record(clock.wallTime() - 50, TimestampType.CREATE_TIME));
            observation.deliveryReceived(record(-1, TimestampType.NO_TIMESTAMP_TYPE));
            observation.deliveryReceived(record(clock.wallTime() + 50, TimestampType.CREATE_TIME));

            var age = meters.get("notification.delivery.record.age").tag("timestamp_type", "CREATE_TIME").timer();
            assertThat(age.count()).isEqualTo(1);
            assertThat(age.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(50);
            assertThat(meters.get("notification.delivery.record.age.unavailable").tag("reason", "MISSING")
                .counter().count()).isEqualTo(1);
            assertThat(meters.get("notification.delivery.record.age.unavailable").tag("reason", "FUTURE")
                .counter().count()).isEqualTo(1);
        } finally {
            meters.close();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void 지표_저장소의_RuntimeException은_전송과_연결_정리를_막지_않는다() throws Exception {
        var provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenThrow(new IllegalStateException());
        var observation = new NotificationSseObservation(provider,
            new NotificationSseProcessIdentity(new NotificationDeliveryProperties()));
        var registry = new NotificationEmitterRegistry(observation);
        var connection = connection();
        registry.register(connection);
        registry.observeRegistration(connection);

        new NotificationSseSender(registry, observation).sendNotification(7L, record(1, TimestampType.CREATE_TIME)
            .value().notification());
        assertThat(registry.remove(connection, NotificationSseCloseReason.SHUTDOWN)).isTrue();

        verify(connection.getEmitter()).send(any(SseEmitter.SseEventBuilder.class));
        assertThat(registry.snapshotAll()).isEmpty();
        assertThat(connection.isClosed()).isTrue();
    }

    private NotificationSseObservation observation(MeterRegistry meters) {
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("meters", meters);
        return new NotificationSseObservation(beans.getBeanProvider(MeterRegistry.class),
            new NotificationSseProcessIdentity(new NotificationDeliveryProperties()));
    }

    private NotificationSseConnection connection() {
        return new NotificationSseConnection(7L, "connection-7", mock(SseEmitter.class), LocalDateTime.now());
    }

    private ConsumerRecord<Long, NotificationDeliveryEvent> record(long timestamp, TimestampType type) {
        var snapshot = new NotificationEventResponse(31L, "LIKE", "", "", null, null, null,
            LocalDateTime.of(2026, 10, 24, 12, 0));
        return new ConsumerRecord<>("delivery", 0, 11, timestamp, type, 0, 0, 7L,
            new NotificationDeliveryEvent(1, "source", 7L, snapshot), new RecordHeaders(), Optional.empty());
    }
}

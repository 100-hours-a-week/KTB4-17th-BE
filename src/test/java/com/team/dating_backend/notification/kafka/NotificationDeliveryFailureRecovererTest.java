package com.team.dating_backend.notification.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.team.dating_backend.notification.alert.NotificationFailureContext;
import com.team.dating_backend.notification.alert.NotificationFailureReporter;
import com.team.dating_backend.notification.config.NotificationKafkaProperties;
import com.team.dating_backend.notification.dto.event.NotificationDeliveryEvent;
import com.team.dating_backend.notification.dto.response.NotificationEventResponse;
import com.team.dating_backend.notification.enums.NotificationFailureCode;
import com.team.dating_backend.notification.exception.NotificationInvalidDeliveryEventException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.stream.Stream;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.listener.ListenerExecutionFailedException;

class NotificationDeliveryFailureRecovererTest {

    private final NotificationFailureReporter reporter = mock(NotificationFailureReporter.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final NotificationKafkaProperties properties = new NotificationKafkaProperties();
    private final NotificationDeliveryFailureRecoverer recoverer = new NotificationDeliveryFailureRecoverer(
        reporter, properties, meters);

    @Test
    void 알림_보고_실패와_알_수_없는_type에도_불량_레코드는_현재_위치를_기록하고_건너뛴다() {
        properties.setDeliveryTopic("delivery");
        var createdAt = LocalDateTime.of(2026, 10, 23, 12, 0);
        var event = new NotificationDeliveryEvent(1, "source", 7L,
            new NotificationEventResponse(31L, "UNKNOWN", "private-title", "private-body", null, null, null,
                createdAt));
        var failure = new ListenerExecutionFailedException("", "process-group",
            new NotificationInvalidDeliveryEventException(new IllegalArgumentException()));
        doThrow(new IllegalStateException()).when(reporter).report(any());

        assertThatCode(() -> recoverer.accept(new ConsumerRecord<>("delivery", 2, 18, 7L, event), failure))
            .doesNotThrowAnyException();

        var captor = ArgumentCaptor.forClass(NotificationFailureContext.class);
        verify(reporter).report(captor.capture());
        var context = captor.getValue();
        assertThat(context.errorCode()).isEqualTo(NotificationFailureCode.DELIVERY_INVALID_EVENT);
        assertThat(context.type()).isNull();
        assertThat(context.requestedAt()).isNull();
        assertThat(context.deliveryDetails())
            .isEqualTo(new NotificationFailureContext.DeliveryFailureDetails(1, 31L, createdAt));
        assertThat(context.kafkaDetails().consumerGroup()).isEqualTo("process-group");
        assertThat(context.kafkaDetails().inputLocation())
            .isEqualTo(new NotificationFailureContext.KafkaLocation("delivery", 2, 18L));
        assertThat(context.kafkaDetails().destinationLocation()).isNull();
        assertThat(context.kafkaDetails().retryable()).isFalse();
        assertThat(meters.get("notification.reporting.failures").tag("operation", "DELIVERY_CONTEXT").counter().count())
            .isEqualTo(1);
    }

    @Test
    void 알_수_없는_처리_실패는_보고_후_원래_예외를_전파한다() {
        properties.setDeliveryTopic("delivery");
        var failure = new IllegalStateException();

        assertThatThrownBy(() -> recoverer.accept(new ConsumerRecord<>("delivery", 0, 0, 7L, null), failure))
            .isSameAs(failure);

        var captor = ArgumentCaptor.forClass(NotificationFailureContext.class);
        verify(reporter).report(captor.capture());
        assertThat(captor.getValue().errorCode()).isEqualTo(NotificationFailureCode.DELIVERY_PROCESSING_FAILED);
        assertThat(captor.getValue().deliveryDetails()).isNull();
        assertThat(captor.getValue().kafkaDetails().retryable()).isNull();
    }

    @ParameterizedTest
    @MethodSource("fatalCauses")
    void 계약_예외_내부의_인터럽트와_Error는_건너뛰지_않는다(Throwable cause) {
        properties.setDeliveryTopic("delivery");
        var failure = new NotificationInvalidDeliveryEventException(cause);
        try {
            assertThatThrownBy(() -> recoverer.accept(new ConsumerRecord<>("delivery", 0, 0, 7L, null), failure))
                .isSameAs(cause instanceof Error ? cause : failure);
            assertThat(Thread.currentThread().isInterrupted()).isEqualTo(cause instanceof InterruptedException);
            verifyNoInteractions(reporter);
        } finally {
            Thread.interrupted();
        }
    }

    private static Stream<Throwable> fatalCauses() {
        return Stream.of(new InterruptedException(), new AssertionError());
    }
}

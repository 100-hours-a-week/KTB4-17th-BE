package com.team.dating_backend.notification.kafka;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;

import com.team.dating_backend.notification.dto.event.NotificationDeliveryEvent;
import com.team.dating_backend.notification.dto.response.NotificationEventResponse;
import com.team.dating_backend.notification.exception.NotificationInvalidDeliveryEventException;
import com.team.dating_backend.notification.service.NotificationDeliveryEventValidator;
import com.team.dating_backend.notification.sse.NotificationSseSender;
import com.team.dating_backend.notification.sse.NotificationEmitterRegistry;
import com.team.dating_backend.notification.sse.NotificationSseObservation;
import com.team.dating_backend.notification.sse.NotificationSseConnection;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class NotificationDeliveryConsumerTest {

    private final NotificationSseSender sender = mock(NotificationSseSender.class);
    private final NotificationEmitterRegistry registry = mock(NotificationEmitterRegistry.class);
    private final NotificationSseObservation observation = mock(NotificationSseObservation.class);
    private final NotificationDeliveryConsumer consumer = new NotificationDeliveryConsumer(
        new NotificationDeliveryEventValidator(), sender, registry, observation);

    @Test
    void 유니코드_경계값과_읽음_시각을_포함한_저장_스냅샷을_그대로_전달한다() {
        var now = LocalDateTime.of(2026, 10, 23, 12, 0);
        var snapshot = new NotificationEventResponse(31L, "LIKE", "😀".repeat(100), "😀".repeat(500),
            "PROFILE", 9L, now, now.minusMinutes(1));
        var event = new NotificationDeliveryEvent(1, "😀".repeat(255), 7L, snapshot);
        when(registry.findByUserId(7L)).thenReturn(List.of(mock(NotificationSseConnection.class)));

        consumer.consume(new ConsumerRecord<>("delivery", 0, 0, 7L, event));

        verify(sender).sendNotification(eq(7L), same(snapshot));
        verify(observation).deliveryProcessed(any(), eq(1), anyLong(), isNull());
    }

    @Test
    void 빈_제목과_본문_및_없는_대상은_기존_계약대로_허용한다() {
        var snapshot = new NotificationEventResponse(31L, "NOTICE", "", "", null, null, null,
            LocalDateTime.of(2026, 10, 23, 12, 0));

        consumer.consume(new ConsumerRecord<>("delivery", 0, 0, 7L,
            new NotificationDeliveryEvent(1, "source", 7L, snapshot)));

        verify(sender).sendNotification(eq(7L), same(snapshot));
        verify(observation).deliveryProcessed(any(), eq(0), anyLong(), isNull());
    }

    @ParameterizedTest
    @MethodSource("invalidEvents")
    void 계약_위반은_SSE에_전달하지_않고_전용_예외로_구분한다(NotificationDeliveryEvent event) {
        var record = new ConsumerRecord<>("delivery", 0, 0, 7L, event);
        assertThatThrownBy(() -> consumer.consume(record))
            .isInstanceOf(NotificationInvalidDeliveryEventException.class);

        verifyNoInteractions(sender);
        verify(observation).deliveryProcessed(eq(record), isNull(), anyLong(),
            any(NotificationInvalidDeliveryEventException.class));
    }

    @ParameterizedTest
    @MethodSource("processingFailures")
    void 처리_실패와_관측_Error가_겹쳐도_원인을_보존하고_정상_처리로_반환하지_않는다(Throwable failure) {
        var snapshot = new NotificationEventResponse(31L, "LIKE", "", "", null, null, null,
            LocalDateTime.of(2026, 10, 24, 12, 0));
        var record = new ConsumerRecord<>("delivery", 0, 0, 7L,
            new NotificationDeliveryEvent(1, "source", 7L, snapshot));
        var observationFailure = new AssertionError();
        doThrow(failure).when(sender).sendNotification(7L, snapshot);
        doThrow(observationFailure).when(observation).deliveryProcessed(eq(record), eq(0), anyLong(), same(failure));

        Throwable expected = failure instanceof Error ? failure : observationFailure;
        Throwable suppressed = failure instanceof Error ? observationFailure : failure;
        assertThatThrownBy(() -> consumer.consume(record)).isSameAs(expected);
        assertThat(expected.getSuppressed()).containsExactly(suppressed);
        verify(observation).deliveryProcessed(eq(record), eq(0), anyLong(), same(failure));
    }

    private static Stream<Throwable> processingFailures() {
        return Stream.of(new IllegalStateException(), new AssertionError());
    }

    private static Stream<NotificationDeliveryEvent> invalidEvents() {
        var now = LocalDateTime.of(2026, 10, 23, 12, 0);
        return Stream.of(
            null,
            new NotificationDeliveryEvent(99, "source", 7L, null),
            new NotificationDeliveryEvent(1, "source", 7L,
                new NotificationEventResponse(31L, "UNKNOWN", "", "", null, null, null, now)),
            new NotificationDeliveryEvent(1, "source", 7L,
                new NotificationEventResponse(31L, "LIKE", "", "", "PROFILE", null, null, now)),
            new NotificationDeliveryEvent(1, "source", 7L,
                new NotificationEventResponse(31L, "LIKE", "😀".repeat(101), "", null, null, null, now)));
    }
}

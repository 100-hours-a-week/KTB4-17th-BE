package com.team.dating_backend.notification.kafka;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.team.dating_backend.notification.dto.event.NotificationDeliveryEvent;
import com.team.dating_backend.notification.dto.response.NotificationEventResponse;
import com.team.dating_backend.notification.exception.NotificationInvalidDeliveryEventException;
import com.team.dating_backend.notification.service.NotificationDeliveryEventValidator;
import com.team.dating_backend.notification.sse.NotificationSseSender;
import java.time.LocalDateTime;
import java.util.stream.Stream;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class NotificationDeliveryConsumerTest {

    private final NotificationSseSender sender = mock(NotificationSseSender.class);
    private final NotificationDeliveryConsumer consumer = new NotificationDeliveryConsumer(
        new NotificationDeliveryEventValidator(), sender);

    @Test
    void 유니코드_경계값과_읽음_시각을_포함한_저장_스냅샷을_그대로_전달한다() {
        var now = LocalDateTime.of(2026, 10, 23, 12, 0);
        var snapshot = new NotificationEventResponse(31L, "LIKE", "😀".repeat(100), "😀".repeat(500),
            "PROFILE", 9L, now, now.minusMinutes(1));
        var event = new NotificationDeliveryEvent(1, "😀".repeat(255), 7L, snapshot);

        consumer.consume(new ConsumerRecord<>("delivery", 0, 0, "7", event));

        verify(sender).sendNotification(eq(7L), same(snapshot));
    }

    @Test
    void 빈_제목과_본문_및_없는_대상은_기존_계약대로_허용한다() {
        var snapshot = new NotificationEventResponse(31L, "NOTICE", "", "", null, null, null,
            LocalDateTime.of(2026, 10, 23, 12, 0));

        consumer.consume(new ConsumerRecord<>("delivery", 0, 0, "7",
            new NotificationDeliveryEvent(1, "source", 7L, snapshot)));

        verify(sender).sendNotification(eq(7L), same(snapshot));
    }

    @ParameterizedTest
    @MethodSource("invalidEvents")
    void 계약_위반은_SSE에_전달하지_않고_전용_예외로_구분한다(NotificationDeliveryEvent event) {
        assertThatThrownBy(() -> consumer.consume(new ConsumerRecord<>("delivery", 0, 0, "7", event)))
            .isInstanceOf(NotificationInvalidDeliveryEventException.class);

        verifyNoInteractions(sender);
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

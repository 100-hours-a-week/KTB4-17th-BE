package com.team.dating_backend.notification.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.notification.dto.response.NotificationConnectedResponse;
import com.team.dating_backend.notification.dto.response.NotificationEventResponse;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class NotificationSseSenderTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 15, 12, 0);

    private NotificationEmitterRegistry registry;
    private NotificationSseSender sender;

    @BeforeEach
    void setUp() {
        registry = mock(NotificationEmitterRegistry.class);
        sender = new NotificationSseSender(registry);
    }

    @Test
    void 초기_이벤트와_heartbeat는_서로_다른_계약으로_전송한다() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        NotificationSseConnection connection = connection("first", emitter);

        sender.sendConnected(connection);
        sender.sendHeartbeat(connection);

        ArgumentCaptor<SseEmitter.SseEventBuilder> events = ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter, times(2)).send(events.capture());
        assertThat(text(events.getAllValues().get(0))).contains("event:connected\n", "data:");
        assertThat(events.getAllValues().get(0).build().stream().map(SseEmitter.DataWithMediaType::getData))
            .contains(new NotificationConnectedResponse("first"));
        assertThat(text(events.getAllValues().get(1))).isEqualTo(":heartbeat\n\n");
    }

    @Test
    void 한_연결의_IO_실패는_그_연결만_제거하고_다음_연결에_같은_알림을_전송한다() throws Exception {
        SseEmitter failedEmitter = mock(SseEmitter.class);
        SseEmitter healthyEmitter = mock(SseEmitter.class);
        NotificationSseConnection failed = connection("failed", failedEmitter);
        NotificationSseConnection healthy = connection("healthy", healthyEmitter);
        NotificationEventResponse response = notification();
        when(registry.findByUserId(1L)).thenReturn(List.of(failed, healthy));
        doThrow(new IOException()).when(failedEmitter).send(any(SseEmitter.SseEventBuilder.class));

        sender.sendNotification(1L, response);

        verify(registry).remove(failed);
        verify(registry, never()).remove(healthy);
        verify(failedEmitter, never()).completeWithError(any());
        ArgumentCaptor<SseEmitter.SseEventBuilder> event = ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(healthyEmitter).send(event.capture());
        assertThat(text(event.getValue())).contains("event:notification\n");
        assertThat(event.getValue().build().stream().map(SseEmitter.DataWithMediaType::getData))
            .contains(response);
    }

    @Test
    void 애플리케이션_전송_오류는_서버_오류_코드로_종료를_요청하고_다음_연결을_처리한다() throws Exception {
        SseEmitter failedEmitter = mock(SseEmitter.class);
        SseEmitter healthyEmitter = mock(SseEmitter.class);
        NotificationSseConnection failed = connection("failed", failedEmitter);
        NotificationSseConnection healthy = connection("healthy", healthyEmitter);
        IllegalStateException failure = new IllegalStateException(new IllegalArgumentException());
        when(registry.findByUserId(1L)).thenReturn(List.of(failed, healthy));
        doThrow(failure).when(failedEmitter).send(any(SseEmitter.SseEventBuilder.class));

        sender.sendNotification(1L, notification());

        verify(registry).remove(failed);
        ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
        verify(failedEmitter).completeWithError(error.capture());
        assertThat(error.getValue()).isInstanceOfSatisfying(NotificationBusinessException.class, exception -> {
            assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.INTERNAL_SERVER_ERROR);
            assertThat(exception.getCause()).isSameAs(failure);
            assertThat(exception.getMessage()).isNull();
        });
        verify(healthyEmitter).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void 종료된_연결에는_heartbeat를_전송하지_않는다() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        NotificationSseConnection connection = connection("closed", emitter);
        connection.markClosed();

        sender.sendHeartbeat(connection);

        verify(emitter, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    private NotificationSseConnection connection(String id, SseEmitter emitter) {
        return new NotificationSseConnection(1L, id, emitter, NOW);
    }

    private NotificationEventResponse notification() {
        return new NotificationEventResponse(10L, "LIKE", "관심", "관심 도착", null, null, null, NOW);
    }

    private String text(SseEmitter.SseEventBuilder event) {
        return event.build().stream()
            .map(SseEmitter.DataWithMediaType::getData)
            .filter(String.class::isInstance)
            .map(String.class::cast)
            .collect(Collectors.joining());
    }
}

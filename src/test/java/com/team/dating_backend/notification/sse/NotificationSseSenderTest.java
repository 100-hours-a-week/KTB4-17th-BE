package com.team.dating_backend.notification.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.eq;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.notification.dto.response.NotificationConnectedResponse;
import com.team.dating_backend.notification.dto.response.NotificationEventResponse;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import com.team.dating_backend.notification.enums.NotificationSseCloseReason;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class NotificationSseSenderTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 15, 12, 0);

    private NotificationEmitterRegistry registry;
    private NotificationSseSender sender;
    private NotificationSseObservation observation;

    @BeforeEach
    void setUp() {
        registry = mock(NotificationEmitterRegistry.class);
        observation = mock(NotificationSseObservation.class);
        sender = new NotificationSseSender(registry, observation);
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

        verify(registry).remove(failed, NotificationSseCloseReason.TRANSPORT_ERROR);
        verify(registry, never()).remove(eq(healthy), any());
        verify(failedEmitter, never()).completeWithError(any());
        verify(observation).sendFailed(eq(failed), eq(NotificationSseObservation.EventKind.NOTIFICATION),
            eq(NotificationSseCloseReason.TRANSPORT_ERROR), any(IOException.class));
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

        verify(registry).remove(failed, NotificationSseCloseReason.SERVER_ERROR);
        ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
        verify(failedEmitter).completeWithError(error.capture());
        assertThat(error.getValue()).isInstanceOfSatisfying(NotificationBusinessException.class, exception -> {
            assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.INTERNAL_SERVER_ERROR);
            assertThat(exception.getCause()).isSameAs(failure);
            assertThat(exception.getMessage()).isNull();
        });
        verify(healthyEmitter).send(any(SseEmitter.SseEventBuilder.class));
        verify(observation).sendFailed(failed, NotificationSseObservation.EventKind.NOTIFICATION,
            NotificationSseCloseReason.SERVER_ERROR, failure);
    }

    @Test
    void 종료된_연결에는_heartbeat를_전송하지_않는다() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        NotificationSseConnection connection = connection("closed", emitter);
        connection.markClosed();

        sender.sendHeartbeat(connection);

        verify(emitter, never()).send(any(SseEmitter.SseEventBuilder.class));
        verifyNoInteractions(observation);
    }

    @Test
    void 완료_경합은_실패로_집계하지_않고_이미_닫힌_연결의_실제_IO_실패는_기록한다() throws Exception {
        var completedEmitter = mock(SseEmitter.class);
        var disconnectedEmitter = mock(SseEmitter.class);
        var completed = connection("completion", completedEmitter);
        var disconnected = connection("transport", disconnectedEmitter);
        when(registry.findByUserId(1L)).thenReturn(List.of(completed, disconnected));
        doAnswer(invocation -> {
            completed.markClosed();
            throw new IllegalStateException();
        }).when(completedEmitter).send(any(SseEmitter.SseEventBuilder.class));
        var failure = new IOException();
        doAnswer(invocation -> {
            disconnected.markClosed();
            throw failure;
        }).when(disconnectedEmitter).send(any(SseEmitter.SseEventBuilder.class));

        sender.sendNotification(1L, notification());

        verify(registry).remove(completed, NotificationSseCloseReason.COMPLETION);
        verify(observation, never()).sendFailed(eq(completed), any(), any(), any());
        verify(observation).sendFailed(disconnected, NotificationSseObservation.EventKind.NOTIFICATION,
            NotificationSseCloseReason.TRANSPORT_ERROR, failure);
    }

    @Test
    void 같은_ID의_내용이_바뀌어도_같은_연결에는_한_번만_전송한다() throws Exception {
        var emitter = mock(SseEmitter.class);
        var connection = connection("first", emitter);
        when(registry.findByUserId(1L)).thenReturn(List.of(connection));
        var original = notification();
        var changed = new NotificationEventResponse(original.notificationId(), "LIKE", "변경 제목", "변경 본문",
            null, null, NOW, NOW);

        sender.sendNotification(1L, original);
        sender.sendNotification(1L, changed);

        var event = ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter).send(event.capture());
        assertThat(event.getValue().build().stream().map(SseEmitter.DataWithMediaType::getData)).contains(original);
        verifyNoInteractions(observation);
    }

    @Test
    void 같은_사용자의_여러_연결은_각각_독립적으로_한_번씩_전송한다() throws Exception {
        var first = connection("first", mock(SseEmitter.class));
        var second = connection("second", mock(SseEmitter.class));
        when(registry.findByUserId(1L)).thenReturn(List.of(first, second));

        sender.sendNotification(1L, notification());
        sender.sendNotification(1L, notification());

        verify(first.getEmitter()).send(any(SseEmitter.SseEventBuilder.class));
        verify(second.getEmitter()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void 전송_성공_후에만_이력을_기록하고_실패한_ID는_기록하지_않는다() throws Exception {
        var emitter = mock(SseEmitter.class);
        var connection = connection("first", emitter);
        when(registry.findByUserId(1L)).thenReturn(List.of(connection));
        doAnswer(invocation -> {
            assertThat(connection.hasSentNotification(10L)).isFalse();
            throw new IOException();
        }).doAnswer(invocation -> {
            assertThat(connection.hasSentNotification(10L)).isFalse();
            return null;
        }).when(emitter).send(any(SseEmitter.SseEventBuilder.class));

        sender.sendNotification(1L, notification());
        assertThat(connection.hasSentNotification(10L)).isFalse();
        sender.sendNotification(1L, notification());

        assertThat(connection.hasSentNotification(10L)).isTrue();
        verify(emitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void 최근_256개만_유지하고_중복_수신은_오래된_ID의_순서를_갱신하지_않는다() throws Exception {
        var emitter = mock(SseEmitter.class);
        var connection = connection("first", emitter);
        when(registry.findByUserId(1L)).thenReturn(List.of(connection));
        for (long id = 1; id <= 256; id++) {
            sender.sendNotification(1L, notification(id));
        }

        sender.sendNotification(1L, notification(1L));
        sender.sendNotification(1L, notification(257L));

        assertThat(connection.hasSentNotification(1L)).isFalse();
        assertThat(connection.hasSentNotification(2L)).isTrue();
        assertThat(connection.hasSentNotification(257L)).isTrue();
        sender.sendNotification(1L, notification(2L));
        sender.sendNotification(1L, notification(1L));
        verify(emitter, times(258)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void 알림_중복_검사는_connected와_heartbeat_반복_전송을_막지_않는다() throws Exception {
        var emitter = mock(SseEmitter.class);
        var connection = connection("first", emitter);
        when(registry.findByUserId(1L)).thenReturn(List.of(connection));

        sender.sendNotification(1L, notification());
        for (int attempt = 0; attempt < 2; attempt++) {
            sender.sendConnected(connection);
            sender.sendHeartbeat(connection);
            sender.sendNotification(1L, notification());
        }

        var events = ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter, times(5)).send(events.capture());
        assertThat(events.getAllValues().stream().map(this::text).filter(value -> value.contains("event:connected")))
            .hasSize(2);
        assertThat(events.getAllValues().stream().map(this::text).filter(value -> value.contains(":heartbeat")))
            .hasSize(2);
    }

    @Test
    void 연결_종료로_이력을_비우고_새_연결은_같은_ID를_받을_수_있다() throws Exception {
        var realRegistry = new NotificationEmitterRegistry(observation);
        var realSender = new NotificationSseSender(realRegistry, observation);
        var first = connection("first", mock(SseEmitter.class));
        realRegistry.register(first);
        realSender.sendNotification(1L, notification());
        assertThat(first.hasSentNotification(10L)).isTrue();

        realRegistry.remove(first);
        var replacement = connection("replacement", mock(SseEmitter.class));
        realRegistry.register(replacement);
        realSender.sendNotification(1L, notification());

        assertThat(first.hasSentNotification(10L)).isFalse();
        verify(first.getEmitter()).send(any(SseEmitter.SseEventBuilder.class));
        verify(replacement.getEmitter()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void 동시_중복_요청은_전송_잠금_안에서_검사하여_한_번만_보낸다() throws Exception {
        var emitter = mock(SseEmitter.class);
        var connection = connection("first", emitter);
        when(registry.findByUserId(1L)).thenReturn(List.of(connection));
        var sending = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var secondThread = new AtomicReference<Thread>();
        doAnswer(invocation -> {
            sending.countDown();
            assertThat(finish.await(5, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(emitter).send(any(SseEmitter.SseEventBuilder.class));

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                var first = executor.submit(() -> sender.sendNotification(1L, notification()));
                assertThat(sending.await(5, TimeUnit.SECONDS)).isTrue();
                var second = executor.submit(() -> {
                    secondThread.set(Thread.currentThread());
                    sender.sendNotification(1L, notification());
                });
                await().atMost(Duration.ofSeconds(5)).until(() -> secondThread.get() != null
                    && secondThread.get().getState() == Thread.State.WAITING);
                finish.countDown();
                first.get(5, TimeUnit.SECONDS);
                second.get(5, TimeUnit.SECONDS);
            } finally {
                finish.countDown();
            }
        }

        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void 전송_중_종료는_네트워크_완료를_기다리지_않고_복귀하며_이력이_다시_생기지_않는다() throws Exception {
        var emitter = mock(SseEmitter.class);
        var connection = connection("first", emitter);
        when(registry.findByUserId(1L)).thenReturn(List.of(connection));
        sender.sendNotification(1L, notification(1L));
        var sending = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        doAnswer(invocation -> {
            sending.countDown();
            assertThat(finish.await(5, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(emitter).send(any(SseEmitter.SseEventBuilder.class));

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                var send = executor.submit(() -> sender.sendNotification(1L, notification(2L)));
                assertThat(sending.await(5, TimeUnit.SECONDS)).isTrue();
                var close = executor.submit(connection::markClosed);
                assertThat(close.get(1, TimeUnit.SECONDS)).isTrue();
                assertThat(connection.hasSentNotification(1L)).isFalse();
                finish.countDown();
                send.get(5, TimeUnit.SECONDS);
            } finally {
                finish.countDown();
            }
        }

        assertThat(connection.hasSentNotification(2L)).isFalse();
        sender.sendNotification(1L, notification(3L));
        verify(emitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    void 알림_ID가_유효하지_않으면_전송하지_않는다(Long id) {
        assertThatThrownBy(() -> sender.sendNotification(1L, notification(id)))
            .isInstanceOf(NotificationBusinessException.class);

        verifyNoInteractions(registry);
    }

    private NotificationSseConnection connection(String id, SseEmitter emitter) {
        return new NotificationSseConnection(1L, id, emitter, NOW);
    }

    private NotificationEventResponse notification() {
        return notification(10L);
    }

    private NotificationEventResponse notification(Long id) {
        return new NotificationEventResponse(id, "LIKE", "관심", "관심 도착", null, null, null, NOW);
    }

    private String text(SseEmitter.SseEventBuilder event) {
        return event.build().stream()
            .map(SseEmitter.DataWithMediaType::getData)
            .filter(String.class::isInstance)
            .map(String.class::cast)
            .collect(Collectors.joining());
    }
}

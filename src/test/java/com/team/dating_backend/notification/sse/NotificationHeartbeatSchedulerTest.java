package com.team.dating_backend.notification.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class NotificationHeartbeatSchedulerTest {

    @Test
    void heartbeat_순회는_실패한_연결만_제거하고_다른_회원의_연결도_처리한다() throws Exception {
        var observation = mock(NotificationSseObservation.class);
        NotificationEmitterRegistry registry = new NotificationEmitterRegistry(observation);
        NotificationSseSender sender = new NotificationSseSender(registry, observation);
        NotificationHeartbeatScheduler scheduler = new NotificationHeartbeatScheduler(registry, sender);
        SseEmitter failedEmitter = mock(SseEmitter.class);
        SseEmitter healthyEmitter = mock(SseEmitter.class);
        NotificationSseConnection failed = new NotificationSseConnection(
            1L, "failed", failedEmitter, LocalDateTime.now());
        NotificationSseConnection healthy = new NotificationSseConnection(
            2L, "healthy", healthyEmitter, LocalDateTime.now());
        registry.register(failed);
        registry.register(healthy);
        doThrow(new IOException()).when(failedEmitter).send(any(SseEmitter.SseEventBuilder.class));

        scheduler.sendHeartbeats();

        assertThat(failed.isClosed()).isTrue();
        assertThat(registry.snapshotAll()).containsExactly(healthy);
        verify(healthyEmitter).send(any(SseEmitter.SseEventBuilder.class));
    }
}

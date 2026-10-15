package com.team.dating_backend.notification.sse;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class NotificationSseConnectionTest {

    @Test
    void 종료_표시는_최초_호출만_성공하고_이후에도_종료_상태를_유지한다() {
        NotificationSseConnection connection = new NotificationSseConnection(
            1L, "connection-1", new SseEmitter(), LocalDateTime.of(2026, 10, 15, 12, 0));

        assertThat(connection.isClosed()).isFalse();
        assertThat(connection.markClosed()).isTrue();
        assertThat(connection.markClosed()).isFalse();
        assertThat(connection.isClosed()).isTrue();
    }
}

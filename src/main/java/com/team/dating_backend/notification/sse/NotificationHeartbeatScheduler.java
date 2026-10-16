package com.team.dating_backend.notification.sse;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NotificationHeartbeatScheduler {

    private final NotificationEmitterRegistry registry;
    private final NotificationSseSender sender;

    @Scheduled(fixedDelayString = "${app.notification.sse.heartbeat-delay-ms:30000}")
    public void sendHeartbeats() {
        for (NotificationSseConnection connection : registry.snapshotAll()) {
            sender.sendHeartbeat(connection);
        }
    }
}

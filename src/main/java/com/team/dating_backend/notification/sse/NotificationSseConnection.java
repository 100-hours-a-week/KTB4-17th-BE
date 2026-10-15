package com.team.dating_backend.notification.sse;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import lombok.AccessLevel;
import lombok.Getter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Getter
public class NotificationSseConnection {

    private final Long userId;
    private final String connectionId;
    private final SseEmitter emitter;
    private final LocalDateTime createdAt;

    @Getter(AccessLevel.NONE)
    private final AtomicBoolean closed = new AtomicBoolean(false);

    @Getter(AccessLevel.NONE)
    private final ReentrantLock sendLock = new ReentrantLock();

    public NotificationSseConnection(
        Long userId,
        String connectionId,
        SseEmitter emitter,
        LocalDateTime createdAt) {
        if (userId == null || userId <= 0
            || connectionId == null || connectionId.isBlank()
            || emitter == null || createdAt == null) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }

        this.userId = userId;
        this.connectionId = connectionId;
        this.emitter = emitter;
        this.createdAt = createdAt;
    }

    public boolean isClosed() {
        return closed.get();
    }

    public boolean markClosed() {
        return closed.compareAndSet(false, true);
    }

    public void lockForSend() {
        sendLock.lock();
    }

    public void unlockForSend() {
        sendLock.unlock();
    }
}

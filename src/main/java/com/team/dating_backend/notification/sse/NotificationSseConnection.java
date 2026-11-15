package com.team.dating_backend.notification.sse;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import lombok.AccessLevel;
import lombok.Getter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Getter
public class NotificationSseConnection {

    private static final int RECENT_NOTIFICATION_LIMIT = 256;

    private final Long userId;
    private final String connectionId;
    private final SseEmitter emitter;
    private final LocalDateTime createdAt;

    @Getter(AccessLevel.NONE)
    private final AtomicBoolean closed = new AtomicBoolean(false);

    @Getter(AccessLevel.NONE)
    private final ReentrantLock sendLock = new ReentrantLock();

    @Getter(AccessLevel.NONE)
    private final LinkedHashSet<Long> sentNotificationIds = new LinkedHashSet<>();

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

    public synchronized boolean markClosed() {
        boolean changed = closed.compareAndSet(false, true);
        sentNotificationIds.clear();
        return changed;
    }

    synchronized boolean hasSentNotification(Long notificationId) {
        return sentNotificationIds.contains(notificationId);
    }

    synchronized void recordSentNotification(Long notificationId) {
        if (isClosed()) {
            return;
        }
        sentNotificationIds.add(notificationId);
        if (sentNotificationIds.size() > RECENT_NOTIFICATION_LIMIT) {
            sentNotificationIds.removeFirst();
        }
    }

    public void lockForSend() {
        sendLock.lock();
    }

    public boolean tryLockForSend(long timeoutNanos) throws InterruptedException {
        if (timeoutNanos < 0) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
        return sendLock.tryLock(timeoutNanos, TimeUnit.NANOSECONDS);
    }

    public void unlockForSend() {
        sendLock.unlock();
    }
}

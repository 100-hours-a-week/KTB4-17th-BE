package com.team.dating_backend.notification.alert;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.notification.config.NotificationAlertProperties;
import com.team.dating_backend.notification.enums.NotificationFailureCode;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class NotificationAlertDispatcher {

    private final Executor executor;
    private final DiscordWebhookClient webhookClient;
    private final NotificationAlertProperties properties;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final Counter sentCounter;
    private final Counter rateLimitedCounter;
    private final Deque<Long> attemptTimes = new ArrayDeque<>();

    public NotificationAlertDispatcher(
        @Qualifier("notificationAlertExecutor") Executor executor,
        DiscordWebhookClient webhookClient,
        NotificationAlertProperties properties,
        MeterRegistry meterRegistry) {
        this.executor = executor;
        this.webhookClient = webhookClient;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.clock = meterRegistry.config().clock();
        this.sentCounter = meterRegistry.counter("notification.alert.sent");
        this.rateLimitedCounter = meterRegistry.counter("notification.alert.suppressed", "reason", "rate_limit");
    }

    public void dispatch(NotificationFailureContext context) {
        if (!properties.isEnabled()) {
            return;
        }
        if (context == null) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }

        try {
            executor.execute(() -> send(context));
        } catch (RejectedExecutionException exception) {
            recordFailure(NotificationFailureCode.ALERT_SUBMISSION_REJECTED, context, exception);
        }
    }

    private void send(NotificationFailureContext context) {
        if (!properties.isEnabled()) {
            return;
        }
        if (!tryAcquire()) {
            rateLimitedCounter.increment();
            return;
        }

        try {
            webhookClient.send(context);
            sentCounter.increment();
        } catch (RuntimeException exception) {
            recordFailure(NotificationFailureCode.ALERT_WEBHOOK_FAILED, context, exception);
        }
    }

    private synchronized boolean tryAcquire() {
        long now = clock.monotonicTime();
        long windowNanos = TimeUnit.MILLISECONDS.toNanos(properties.getRateLimitWindowMs());
        while (!attemptTimes.isEmpty() && now - attemptTimes.peekFirst() >= windowNanos) {
            attemptTimes.removeFirst();
        }
        if (attemptTimes.size() >= properties.getMaxMessagesPerWindow()) {
            return false;
        }
        attemptTimes.addLast(now);
        return true;
    }

    private void recordFailure(
        NotificationFailureCode errorCode,
        NotificationFailureContext context,
        RuntimeException exception) {
        meterRegistry.counter("notification.alert.failures", "error_code", errorCode.name()).increment();
        log.error("Notification alert failed. errorCode={}, originalErrorCode={}, exceptionType={}, causeType={}",
            errorCode,
            context.errorCode(),
            exception.getClass().getSimpleName(),
            exception.getCause() == null ? null : exception.getCause().getClass().getSimpleName());
    }
}

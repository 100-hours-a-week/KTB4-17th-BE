package com.team.dating_backend.notification.sse;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.notification.config.NotificationSseProperties;
import com.team.dating_backend.notification.enums.NotificationSseCloseReason;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.Ordered;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationSseShutdownCoordinator implements ApplicationListener<ContextClosedEvent>, Ordered {

    private static final String DELIVERY_LISTENER_ID = "notificationDeliveryConsumer";
    private static final long DRAIN_CHECK_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(50);

    private final NotificationSseProperties properties;
    private final NotificationSseLifecycle lifecycle;
    private final NotificationEmitterRegistry registry;
    private final ObjectProvider<KafkaListenerEndpointRegistry> listenerRegistryProvider;
    private final NotificationSseProcessIdentity processIdentity;
    private final ApplicationContext applicationContext;

    private boolean shutdownStarted;
    private boolean interrupted;

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public boolean supportsAsyncExecution() {
        return false;
    }

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        if (event.getApplicationContext() == applicationContext) {
            drainAndStop();
        }
    }

    public synchronized void drainAndStop() {
        if (shutdownStarted) {
            return;
        }
        shutdownStarted = true;
        interrupted = Thread.interrupted();
        long startedAtNanos = System.nanoTime();
        long drainNanos = 0;
        CloseResult closeResult = null;
        ConsumerStopResult stopResult = ConsumerStopResult.NOT_REQUESTED;
        Error failure = null;
        try {
            lifecycle.beginDrain();
            recordStarted();
            long drainStartedAtNanos = System.nanoTime();
            try {
                awaitDrain();
            } catch (RuntimeException exception) {
                recordFailure("DRAIN", null, exception);
            } finally {
                drainNanos = System.nanoTime() - drainStartedAtNanos;
            }
            closeResult = closeConnections();
        } catch (RuntimeException exception) {
            recordFailure("SHUTDOWN", null, exception);
        } catch (Error error) {
            failure = error;
            throw error;
        } finally {
            long stopStartedAtNanos = System.nanoTime();
            try {
                stopResult = stopConsumer();
            } catch (Error stopError) {
                if (failure != null) {
                    if (failure != stopError) {
                        failure.addSuppressed(stopError);
                    }
                } else {
                    failure = stopError;
                    throw stopError;
                }
            } finally {
                try {
                    if (failure == null) {
                        recordCompleted(System.nanoTime() - startedAtNanos, drainNanos, closeResult, stopResult,
                            System.nanoTime() - stopStartedAtNanos);
                    }
                } finally {
                    skipWaits();
                    if (interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
    }

    private void awaitDrain() {
        long startedAtNanos = System.nanoTime();
        long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(properties.getDrainTimeoutMs());
        while (!registry.snapshotAll().isEmpty() && !skipWaits()) {
            long waitNanos = remaining(startedAtNanos, timeoutNanos);
            if (waitNanos == 0) {
                return;
            }
            try {
                TimeUnit.NANOSECONDS.sleep(Math.min(waitNanos, DRAIN_CHECK_INTERVAL_NANOS));
            } catch (InterruptedException exception) {
                interrupted = true;
                recordFailure("DRAIN_INTERRUPTED", null, exception);
                return;
            }
        }
    }

    private CloseResult closeConnections() {
        long startedAtNanos = System.nanoTime();
        long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(properties.getCloseTimeoutMs());
        var connections = registry.snapshotAll();
        int completed = 0;
        int incomplete = 0;
        int failed = 0;
        int skipped = 0;
        for (NotificationSseConnection connection : connections) {
            boolean locked = false;
            try {
                long waitNanos = skipWaits() ? 0 : remaining(startedAtNanos, timeoutNanos);
                try {
                    locked = connection.tryLockForSend(waitNanos);
                } catch (InterruptedException exception) {
                    interrupted = true;
                    recordFailure("CLOSE_INTERRUPTED", connection, exception);
                }
                if (!registry.remove(connection, NotificationSseCloseReason.SHUTDOWN)) {
                    skipped++;
                } else if (!locked) {
                    incomplete++;
                    recordFailure("CLOSE_LOCK_UNAVAILABLE", connection, null);
                } else {
                    connection.getEmitter().complete();
                    completed++;
                }
            } catch (RuntimeException exception) {
                failed++;
                connection.markClosed();
                try {
                    registry.remove(connection, NotificationSseCloseReason.SHUTDOWN);
                } catch (RuntimeException removalFailure) {
                    recordFailure("CLOSE_REMOVE", connection, removalFailure);
                }
                recordFailure("CLOSE", connection, exception);
            } finally {
                if (locked) {
                    connection.unlockForSend();
                }
            }
        }
        return new CloseResult(connections.size(), completed, incomplete, failed, skipped,
            System.nanoTime() - startedAtNanos);
    }

    private ConsumerStopResult stopConsumer() {
        long startedAtNanos = System.nanoTime();
        skipWaits();
        try {
            KafkaListenerEndpointRegistry endpoints = listenerRegistryProvider.getIfAvailable();
            MessageListenerContainer container = endpoints == null
                ? null
                : endpoints.getListenerContainer(DELIVERY_LISTENER_ID);
            if (container == null) {
                return ConsumerStopResult.NOT_REGISTERED;
            }
            if (!container.isRunning()) {
                return ConsumerStopResult.NOT_RUNNING;
            }
            var stopped = new CountDownLatch(1);
            container.stop(stopped::countDown);
            if (stopped.getCount() == 0) {
                return ConsumerStopResult.STOPPED;
            }
            if (skipWaits()) {
                recordFailure("CONSUMER_STOP_INTERRUPTED", null, null);
                return ConsumerStopResult.INTERRUPTED;
            }
            try {
                long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(properties.getConsumerStopTimeoutMs());
                if (stopped.await(remaining(startedAtNanos, timeoutNanos), TimeUnit.NANOSECONDS)) {
                    return ConsumerStopResult.STOPPED;
                }
                recordFailure("CONSUMER_STOP_TIMEOUT", null, null);
                return ConsumerStopResult.TIMED_OUT;
            } catch (InterruptedException exception) {
                interrupted = true;
                recordFailure("CONSUMER_STOP_INTERRUPTED", null, exception);
                return ConsumerStopResult.INTERRUPTED;
            }
        } catch (RuntimeException exception) {
            recordFailure("CONSUMER_STOP", null, exception);
            return ConsumerStopResult.FAILED;
        }
    }

    private boolean skipWaits() {
        if (Thread.interrupted()) {
            interrupted = true;
        }
        return interrupted;
    }

    private long remaining(long startedAtNanos, long timeoutNanos) {
        return Math.max(0, timeoutNanos - (System.nanoTime() - startedAtNanos));
    }

    private void recordStarted() {
        try {
            log.info("SSE drain started. processId={}, consumerGroup={}, drainTimeoutMs={}",
                processIdentity.getProcessId(), processIdentity.getGroupId(), properties.getDrainTimeoutMs());
        } catch (RuntimeException exception) {
            recordFailure("START_LOG", null, exception);
        }
    }

    private void recordCompleted(
        long totalNanos,
        long drainNanos,
        CloseResult closeResult,
        ConsumerStopResult stopResult,
        long stopNanos) {
        skipWaits();
        try {
            log.info(
                "SSE shutdown finished. processId={}, consumerGroup={}, totalNanos={}, drainNanos={}, closeNanos={}, connections={}, completeRequests={}, incomplete={}, failed={}, skipped={}, consumerStopResult={}, consumerStopNanos={}, interrupted={}",
                processIdentity.getProcessId(), processIdentity.getGroupId(), totalNanos, drainNanos,
                closeResult == null ? null : closeResult.durationNanos(),
                closeResult == null ? null : closeResult.total(),
                closeResult == null ? null : closeResult.completed(),
                closeResult == null ? null : closeResult.incomplete(),
                closeResult == null ? null : closeResult.failed(),
                closeResult == null ? null : closeResult.skipped(), stopResult, stopNanos, interrupted);
        } catch (RuntimeException exception) {
            recordFailure("RESULT_LOG", null, exception);
        }
    }

    private void recordFailure(String operation, NotificationSseConnection connection, Throwable exception) {
        try {
            log.error(
                "errorCode={}, shutdownOperation={}, processId={}, consumerGroup={}, userId={}, connectionId={}, exceptionType={}, causeType={}, interrupted={}",
                CommonErrorCode.INTERNAL_SERVER_ERROR, operation, processIdentity.getProcessId(),
                processIdentity.getGroupId(), connection == null ? null : connection.getUserId(),
                connection == null ? null : connection.getConnectionId(),
                exception == null ? null : exception.getClass().getSimpleName(),
                exception == null || exception.getCause() == null
                    ? null
                    : exception.getCause().getClass().getSimpleName(),
                interrupted);
        } catch (RuntimeException ignored) {}
    }

    private record CloseResult(int total, int completed, int incomplete, int failed, int skipped, long durationNanos) {}

    private enum ConsumerStopResult {
        NOT_REQUESTED,
        NOT_REGISTERED,
        NOT_RUNNING,
        STOPPED,
        TIMED_OUT,
        INTERRUPTED,
        FAILED;
    }
}

package com.team.dating_backend.notification.kafka;

import com.team.dating_backend.notification.alert.NotificationFailureReporter;
import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import com.team.dating_backend.notification.enums.NotificationFailureCode;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class NotificationPublishDispatcher {

    private final Executor executor;
    private final NotificationRequestProducer producer;
    private final NotificationFailureReporter failureReporter;

    public NotificationPublishDispatcher(
        @Qualifier("notificationPublishExecutor") Executor executor,
        NotificationRequestProducer producer,
        NotificationFailureReporter failureReporter) {
        this.executor = executor;
        this.producer = producer;
        this.failureReporter = failureReporter;
    }

    public void dispatch(NotificationCreateRequested request) {
        try {
            executor.execute(() -> publish(request));
        } catch (RejectedExecutionException exception) {
            failureReporter.report(NotificationFailureCode.PUBLISH_SUBMISSION_REJECTED, request, exception);
        }
    }

    private void publish(NotificationCreateRequested request) {
        try {
            producer.publishAndAwait(request);
        } catch (ExecutionException exception) {
            failureReporter.report(NotificationFailureCode.PUBLISH_RESULT_FAILED, request, exception);
        } catch (TimeoutException exception) {
            failureReporter.report(NotificationFailureCode.PUBLISH_RESULT_TIMEOUT, request, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            failureReporter.report(NotificationFailureCode.PUBLISH_INTERRUPTED, request, exception);
        } catch (RuntimeException exception) {
            failureReporter.report(NotificationFailureCode.PUBLISH_CALL_FAILED, request, exception);
        }
    }
}

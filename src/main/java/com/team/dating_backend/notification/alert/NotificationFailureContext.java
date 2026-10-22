package com.team.dating_backend.notification.alert;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import com.team.dating_backend.notification.enums.NotificationFailureCode;
import com.team.dating_backend.notification.enums.NotificationType;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import java.time.Instant;

public record NotificationFailureContext(
    NotificationFailureCode errorCode,
    String sourceEventId,
    Long recipientUserId,
    NotificationType type,
    Instant requestedAt,
    Instant occurredAt,
    String exceptionType,
    String causeType,
    KafkaFailureDetails kafkaDetails) {

    public NotificationFailureContext {
        if (errorCode == null || occurredAt == null) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    public NotificationFailureContext(
        NotificationFailureCode errorCode,
        String sourceEventId,
        Long recipientUserId,
        NotificationType type,
        Instant requestedAt,
        Instant occurredAt,
        String exceptionType,
        String causeType) {
        this(errorCode, sourceEventId, recipientUserId, type, requestedAt, occurredAt, exceptionType, causeType, null);
    }

    public static NotificationFailureContext from(
        NotificationFailureCode errorCode,
        NotificationCreateRequested request,
        Throwable exception,
        Instant occurredAt) {
        return from(errorCode, request, exception, occurredAt, null);
    }

    public static NotificationFailureContext from(
        NotificationFailureCode errorCode,
        NotificationCreateRequested request,
        Throwable exception,
        Instant occurredAt,
        KafkaFailureDetails kafkaDetails) {
        return new NotificationFailureContext(
            errorCode,
            request == null ? null : request.sourceEventId(),
            request == null ? null : request.recipientUserId(),
            request == null ? null : request.type(),
            request == null ? null : request.requestedAt(),
            occurredAt,
            exceptionType(exception),
            causeType(exception),
            kafkaDetails);
    }

    public FailureStage failureStage() {
        return switch (errorCode) {
            case PUBLISH_SUBMISSION_REJECTED -> FailureStage.PUBLISH_SUBMISSION;
            case PUBLISH_CALL_FAILED -> FailureStage.PUBLISH;
            case PUBLISH_RESULT_FAILED, PUBLISH_RESULT_TIMEOUT, PUBLISH_INTERRUPTED -> FailureStage.PUBLISH_RESULT;
            case RETRY_PUBLISH_FAILED -> FailureStage.RETRY_PUBLISH;
            case DLT_PUBLISH_FAILED -> FailureStage.DLT_PUBLISH;
            case DLT_RECORDED -> FailureStage.CREATION_PROCESSING;
            case ALERT_SUBMISSION_REJECTED -> FailureStage.ALERT_SUBMISSION;
            case ALERT_WEBHOOK_FAILED -> FailureStage.ALERT_WEBHOOK;
        };
    }

    private static String exceptionType(Throwable exception) {
        return exception == null ? null : exception.getClass().getSimpleName();
    }

    private static String causeType(Throwable exception) {
        return exception == null ? null : exceptionType(exception.getCause());
    }

    public enum FailureStage {
        PUBLISH_SUBMISSION,
        PUBLISH,
        PUBLISH_RESULT,
        CREATION_PROCESSING,
        RETRY_PUBLISH,
        DLT_PUBLISH,
        ALERT_SUBMISSION,
        ALERT_WEBHOOK
    }

    public record KafkaLocation(String topic, Integer partition, Long offset) {

        public KafkaLocation {
            if (topic == null || topic.isBlank()
                || (partition != null && partition < 0)
                || (offset != null && (offset < 0 || partition == null))) {
                throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
            }
        }
    }

    public record KafkaFailureDetails(
        String consumerGroup,
        KafkaLocation originalLocation,
        KafkaLocation inputLocation,
        KafkaLocation destinationLocation,
        Integer attempt,
        Boolean retryable,
        String processingExceptionType,
        String processingCauseType) {

        public KafkaFailureDetails {
            if (attempt != null && attempt < 1) {
                throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
            }
        }

        public static KafkaFailureDetails from(
            String consumerGroup,
            KafkaLocation originalLocation,
            KafkaLocation inputLocation,
            KafkaLocation destinationLocation,
            Integer attempt,
            Boolean retryable,
            Throwable processingException) {
            return new KafkaFailureDetails(
                consumerGroup,
                originalLocation,
                inputLocation,
                destinationLocation,
                attempt,
                retryable,
                exceptionType(processingException),
                causeType(processingException));
        }
    }
}

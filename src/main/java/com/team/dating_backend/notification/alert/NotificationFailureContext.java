package com.team.dating_backend.notification.alert;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import com.team.dating_backend.notification.dto.event.NotificationDeliveryEvent;
import com.team.dating_backend.notification.entity.NotificationRequestOutbox;
import com.team.dating_backend.notification.enums.NotificationFailureCode;
import com.team.dating_backend.notification.enums.NotificationOutboxStatus;
import com.team.dating_backend.notification.enums.NotificationType;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import java.time.Instant;
import java.time.LocalDateTime;

public record NotificationFailureContext(
    NotificationFailureCode errorCode,
    String sourceEventId,
    Long recipientUserId,
    NotificationType type,
    Instant requestedAt,
    Instant occurredAt,
    String exceptionType,
    String causeType,
    KafkaFailureDetails kafkaDetails,
    DeliveryFailureDetails deliveryDetails,
    OutboxFailureDetails outboxDetails) {

    public NotificationFailureContext {
        if (errorCode == null || occurredAt == null) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    public NotificationFailureContext(
        NotificationFailureCode errorCode, String sourceEventId, Long recipientUserId, NotificationType type,
        Instant requestedAt, Instant occurredAt, String exceptionType, String causeType,
        KafkaFailureDetails kafkaDetails, DeliveryFailureDetails deliveryDetails) {
        this(errorCode, sourceEventId, recipientUserId, type, requestedAt, occurredAt, exceptionType, causeType,
            kafkaDetails, deliveryDetails, null);
    }

    public NotificationFailureContext(
        NotificationFailureCode errorCode,
        String sourceEventId,
        Long recipientUserId,
        NotificationType type,
        Instant requestedAt,
        Instant occurredAt,
        String exceptionType,
        String causeType,
        KafkaFailureDetails kafkaDetails) {
        this(errorCode, sourceEventId, recipientUserId, type, requestedAt, occurredAt, exceptionType, causeType,
            kafkaDetails, null);
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

    public static NotificationFailureContext fromDelivery(
        NotificationFailureCode errorCode,
        NotificationDeliveryEvent event,
        Throwable exception,
        Instant occurredAt,
        KafkaFailureDetails kafkaDetails) {
        var notification = event == null ? null : event.notification();
        DeliveryFailureDetails details = event == null
            ? null
            : new DeliveryFailureDetails(
                notification == null ? null : notification.notificationId(),
                notification == null ? null : notification.createdAt());
        return new NotificationFailureContext(
            errorCode,
            event == null ? null : event.sourceEventId(),
            event == null ? null : event.recipientUserId(),
            notificationType(notification == null ? null : notification.type()),
            null,
            occurredAt,
            exceptionType(exception),
            causeType(exception),
            kafkaDetails,
            details);
    }

    public FailureStage failureStage() {
        return switch (errorCode) {
            case OUTBOX_RESTORE_FAILED -> FailureStage.OUTBOX_RESTORE;
            case OUTBOX_CREATION_FAILED -> FailureStage.CREATION_PROCESSING;
            case OUTBOX_DELIVERY_FAILED, PUBLISH_INTERRUPTED -> FailureStage.PUBLISH_RESULT;
            case OUTBOX_STATE_UPDATE_FAILED -> FailureStage.OUTBOX_STATE_UPDATE;
            case OUTBOX_CLAIM_FAILED, OUTBOX_CLAIM_LOST -> FailureStage.OUTBOX_CLAIM;
            case OUTBOX_RELEASE_FAILED -> FailureStage.OUTBOX_RELEASE;
            case DELIVERY_DESERIALIZATION_FAILED, DELIVERY_INVALID_EVENT,
                DELIVERY_PROCESSING_FAILED -> FailureStage.DELIVERY_CONSUMPTION;
            case ALERT_SUBMISSION_REJECTED -> FailureStage.ALERT_SUBMISSION;
            case ALERT_WEBHOOK_FAILED -> FailureStage.ALERT_WEBHOOK;
        };
    }

    public static NotificationFailureContext fromWorker(NotificationFailureCode errorCode,
        NotificationRequestOutbox outbox, NotificationCreateRequested request, Throwable exception,
        Instant occurredAt, Integer failureCount, NotificationOutboxStatus status, Boolean retryable) {
        return new NotificationFailureContext(errorCode, outbox.getSourceEventId(), outbox.getRecipientUserId(),
            outbox.getType(), request == null ? null : request.requestedAt(), occurredAt,
            exceptionType(exception), causeType(exception), null, null,
            new OutboxFailureDetails(outbox.getId(), failureCount, status, retryable));
    }

    private static NotificationType notificationType(String type) {
        if (type == null) {
            return null;
        }
        try {
            return NotificationType.valueOf(type);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String exceptionType(Throwable exception) {
        return exception == null ? null : exception.getClass().getSimpleName();
    }

    private static String causeType(Throwable exception) {
        return exception == null ? null : exceptionType(exception.getCause());
    }

    public enum FailureStage {
        OUTBOX_CLAIM,
        OUTBOX_RELEASE,
        OUTBOX_RESTORE,
        OUTBOX_STATE_UPDATE,
        PUBLISH_RESULT,
        CREATION_PROCESSING,
        DELIVERY_CONSUMPTION,
        ALERT_SUBMISSION,
        ALERT_WEBHOOK
    }

    public record DeliveryFailureDetails(Long notificationId, LocalDateTime createdAt) {}

    public record OutboxFailureDetails(Long outboxId, Integer failureCount, NotificationOutboxStatus status,
        Boolean retryable) {}

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

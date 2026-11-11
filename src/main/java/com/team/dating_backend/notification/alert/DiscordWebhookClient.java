package com.team.dating_backend.notification.alert;

import com.team.dating_backend.common.alert.DiscordWebhookException;
import com.team.dating_backend.common.alert.DiscordWebhookSender;
import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.notification.alert.NotificationFailureContext.DeliveryFailureDetails;
import com.team.dating_backend.notification.alert.NotificationFailureContext.KafkaFailureDetails;
import com.team.dating_backend.notification.alert.NotificationFailureContext.KafkaLocation;
import com.team.dating_backend.notification.config.NotificationAlertProperties;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class DiscordWebhookClient {

    private final DiscordWebhookSender sender;
    private final NotificationAlertProperties properties;

    public DiscordWebhookClient(
        @Qualifier("notificationDiscordWebhookSender") DiscordWebhookSender sender,
        NotificationAlertProperties properties) {
        this.sender = sender;
        this.properties = properties;
    }

    public void send(NotificationFailureContext context) {
        if (!properties.isEnabled()) {
            return;
        }
        if (context == null || !properties.isWebhookValid()) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }

        try {
            sender.send(properties.getWebhookUrl(), "알림 처리 실패", fields(context));
        } catch (DiscordWebhookException exception) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR, exception);
        }
    }

    private Map<String, Object> fields(NotificationFailureContext context) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("environment", properties.getEnvironment());
        fields.put("errorCode", context.errorCode());
        fields.put("failureStage", context.failureStage());
        fields.put("sourceEventId", context.sourceEventId());
        fields.put("recipientUserId", context.recipientUserId());
        fields.put("type", context.type());
        fields.put("requestedAt", context.requestedAt());
        fields.put("occurredAt", context.occurredAt());
        fields.put("exceptionType", context.exceptionType());
        fields.put("causeType", context.causeType());

        var outbox = context.outboxDetails();
        if (outbox != null) {
            fields.put("outboxId", outbox.outboxId());
            fields.put("failureCount", outbox.failureCount());
            fields.put("outboxStatus", outbox.status());
            fields.put("retryable", outbox.retryable());
        }

        KafkaFailureDetails details = context.kafkaDetails();
        if (details != null) {
            fields.put("consumerGroup", details.consumerGroup());
            addLocation(fields, "original", details.originalLocation());
            addLocation(fields, "input", details.inputLocation());
            addLocation(fields, "destination", details.destinationLocation());
            fields.put("attempt", details.attempt());
            fields.put("retryable", details.retryable());
            fields.put("processingExceptionType", details.processingExceptionType());
            fields.put("processingCauseType", details.processingCauseType());
        }

        DeliveryFailureDetails delivery = context.deliveryDetails();
        if (delivery != null) {
            fields.put("notificationId", delivery.notificationId());
            fields.put("notificationCreatedAt", delivery.createdAt());
        }

        return fields;
    }

    private void addLocation(Map<String, Object> fields, String prefix, KafkaLocation location) {
        fields.put(prefix + "Topic", location == null ? null : location.topic());
        fields.put(prefix + "Partition", location == null ? null : location.partition());
        fields.put(prefix + "Offset", location == null ? null : location.offset());
    }

}

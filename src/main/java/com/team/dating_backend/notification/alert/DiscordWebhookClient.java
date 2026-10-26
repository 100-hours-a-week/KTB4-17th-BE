package com.team.dating_backend.notification.alert;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.notification.alert.NotificationFailureContext.DeliveryFailureDetails;
import com.team.dating_backend.notification.alert.NotificationFailureContext.KafkaFailureDetails;
import com.team.dating_backend.notification.alert.NotificationFailureContext.KafkaLocation;
import com.team.dating_backend.notification.config.NotificationAlertProperties;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

@Component
public class DiscordWebhookClient {

    private static final int MAX_FIELD_LENGTH = 128;
    private static final int MAX_CONTENT_LENGTH = 2_000;
    private static final String CONTENT_HEADER = "@everyone 알림 처리 실패\n```text\n";
    private static final String CONTENT_FOOTER = "```\n";

    private final RestClient restClient;
    private final NotificationAlertProperties properties;

    public DiscordWebhookClient(
        @Qualifier("notificationDiscordRestClient") RestClient restClient,
        NotificationAlertProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    public void send(NotificationFailureContext context) {
        if (!properties.isEnabled()) {
            return;
        }
        if (context == null || !properties.isWebhookValid()) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }

        var response = restClient.post()
            .uri(URI.create(properties.getWebhookUrl() + "?wait=true"))
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .body(Map.of(
                "content", content(context),
                "allowed_mentions", Map.of("parse", List.of("everyone"))))
            .retrieve()
            .toEntity(JsonNode.class);

        JsonNode body = response.getBody();
        if (response.getStatusCode() != HttpStatus.OK
            || body == null
            || !body.path("id").isString()
            || !body.path("id").stringValue().matches("[0-9]+")) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    private String content(NotificationFailureContext context) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("environment", field(properties.getEnvironment()));
        fields.put("errorCode", field(context.errorCode()));
        fields.put("failureStage", field(context.failureStage()));
        fields.put("sourceEventId", field(context.sourceEventId()));
        fields.put("recipientUserId", field(context.recipientUserId()));
        fields.put("type", field(context.type()));
        fields.put("requestedAt", field(context.requestedAt()));
        fields.put("occurredAt", field(context.occurredAt()));
        fields.put("exceptionType", field(context.exceptionType()));
        fields.put("causeType", field(context.causeType()));

        KafkaFailureDetails details = context.kafkaDetails();
        if (details != null) {
            fields.put("consumerGroup", field(details.consumerGroup()));
            addLocation(fields, "original", details.originalLocation());
            addLocation(fields, "input", details.inputLocation());
            addLocation(fields, "destination", details.destinationLocation());
            fields.put("attempt", field(details.attempt()));
            fields.put("retryable", field(details.retryable()));
            fields.put("processingExceptionType", field(details.processingExceptionType()));
            fields.put("processingCauseType", field(details.processingCauseType()));
        }

        DeliveryFailureDetails delivery = context.deliveryDetails();
        if (delivery != null) {
            fields.put("deliverySchemaVersion", field(delivery.schemaVersion()));
            fields.put("notificationId", field(delivery.notificationId()));
            fields.put("notificationCreatedAt", field(delivery.createdAt()));
        }

        int available = MAX_CONTENT_LENGTH - CONTENT_HEADER.length() - CONTENT_FOOTER.length()
            - fields.keySet().stream().mapToInt(name -> name.length() + 3).sum();
        int lower = 1;
        int upper = fields.values().stream().mapToInt(String::length).max().orElse(1);
        while (lower < upper) {
            int candidate = (lower + upper + 1) / 2;
            int length = fields.values().stream().mapToInt(value -> Math.min(value.length(), candidate)).sum();
            if (length <= available) {
                lower = candidate;
            } else {
                upper = candidate - 1;
            }
        }

        var content = new StringBuilder(CONTENT_HEADER);
        for (var entry : fields.entrySet()) {
            content.append(entry.getKey()).append(": ").append(limit(entry.getValue(), lower)).append('\n');
        }
        return content.append(CONTENT_FOOTER).toString();
    }

    private void addLocation(Map<String, String> fields, String prefix, KafkaLocation location) {
        fields.put(prefix + "Topic", field(location == null ? null : location.topic()));
        fields.put(prefix + "Partition", field(location == null ? null : location.partition()));
        fields.put(prefix + "Offset", field(location == null ? null : location.offset()));
    }

    private String limit(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        int end = maxLength - 1;
        if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end) + "…";
    }

    private String field(Object value) {
        if (value == null) {
            return "-";
        }
        String text = value.toString().replaceAll("[\\p{Cntrl}\\p{Cf}\\p{Zl}\\p{Zp}`]", " ").strip();
        int length = Math.min(text.codePointCount(0, text.length()), MAX_FIELD_LENGTH);
        return text.isEmpty() ? "-" : text.substring(0, text.offsetByCodePoints(0, length));
    }
}

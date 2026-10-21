package com.team.dating_backend.notification.alert;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.notification.config.NotificationAlertProperties;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import java.net.URI;
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
        return """
            @everyone 알림 처리 실패
            ```text
            environment: %s
            errorCode: %s
            sourceEventId: %s
            recipientUserId: %s
            type: %s
            requestedAt: %s
            occurredAt: %s
            exceptionType: %s
            causeType: %s
            ```
            """.formatted(
            field(properties.getEnvironment()),
            field(context.errorCode()),
            field(context.sourceEventId()),
            field(context.recipientUserId()),
            field(context.type()),
            field(context.requestedAt()),
            field(context.occurredAt()),
            field(context.exceptionType()),
            field(context.causeType()));
    }

    private String field(Object value) {
        if (value == null) {
            return "-";
        }
        String text = value.toString().replaceAll("[\\p{Cntrl}`]", " ").strip();
        int length = Math.min(text.codePointCount(0, text.length()), MAX_FIELD_LENGTH);
        return text.isEmpty() ? "-" : text.substring(0, text.offsetByCodePoints(0, length));
    }
}

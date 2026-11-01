package com.team.dating_backend.notification.config;

import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "app.notification.outbox.claim")
public class NotificationOutboxClaimProperties {

    @Positive(message = "INTERNAL_SERVER_ERROR")
    private long durationMs = 60_000L;
}

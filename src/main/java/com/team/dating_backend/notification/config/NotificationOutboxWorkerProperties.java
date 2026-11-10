package com.team.dating_backend.notification.config;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "app.notification.outbox.worker")
public class NotificationOutboxWorkerProperties {

    private boolean enabled;

    @Positive(message = "INTERNAL_SERVER_ERROR")
    private long fixedDelayMs = 1000L;

    @Positive(message = "INTERNAL_SERVER_ERROR")
    private int maxRequestsPerRun = 100;

    @PositiveOrZero(message = "INTERNAL_SERVER_ERROR")
    private int shutdownWaitSeconds = 20;
}

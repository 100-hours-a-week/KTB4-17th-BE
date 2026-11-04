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
@ConfigurationProperties(prefix = "app.notification.sse")
public class NotificationSseProperties {

    @Positive
    private long timeoutMs = 1_800_000L;

    @Positive
    private long heartbeatDelayMs = 30_000L;

    @PositiveOrZero
    private long drainTimeoutMs = 10_000L;

    @Positive
    private long closeTimeoutMs = 5_000L;

    @Positive
    private long consumerStopTimeoutMs = 10_000L;
}

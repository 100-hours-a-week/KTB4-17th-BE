package com.team.dating_backend.notification.config;

import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "app.notification.retry")
public class NotificationRetryProperties {

    @Positive
    private int maxAttempts = 4;

    @Positive
    private long delayMs = 5_000L;

    @Positive
    private int concurrency = 1;

    @Positive
    private long publishResultTimeoutMs = 15_000L;

}

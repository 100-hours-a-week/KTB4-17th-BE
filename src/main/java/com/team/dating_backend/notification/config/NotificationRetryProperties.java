package com.team.dating_backend.notification.config;

import jakarta.validation.constraints.AssertTrue;
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
    private int maxAttempts = 7;

    @Positive
    private long initialDelayMs = 1_000L;

    private double multiplier = 2.0;

    @Positive
    private long maxDelayMs = 30_000L;

    @Positive
    private int concurrency = 1;

    @Positive
    private long publishResultTimeoutMs = 15_000L;

    @AssertTrue
    public boolean isBackoffValid() {
        return Double.isFinite(multiplier)
            && multiplier > 1.0
            && maxDelayMs > initialDelayMs;
    }
}

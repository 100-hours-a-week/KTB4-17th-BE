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
@ConfigurationProperties(prefix = "app.notification.outbox.retry")
public class NotificationOutboxRetryProperties {

    @Positive(message = "INTERNAL_SERVER_ERROR")
    private int maxFailures = 5;

    @Positive(message = "INTERNAL_SERVER_ERROR")
    private long initialDelayMs = 1000L;

    @Positive(message = "INTERNAL_SERVER_ERROR")
    private long maxDelayMs = 60_000L;

    @AssertTrue(message = "INTERNAL_SERVER_ERROR")
    public boolean isDelayRangeValid() {
        return maxDelayMs >= initialDelayMs;
    }
}

package com.team.dating_backend.notification.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "app.notification.delivery")
public class NotificationDeliveryProperties {

    private boolean enabled = false;

    @NotBlank
    private String groupIdPrefix = "local-notification-sse-v1";

    @Positive
    private int concurrency = 1;

    @Positive
    private int maxPollRecords = 1;

    @Positive
    private int maxPollIntervalMs = 300_000;
}

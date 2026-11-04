package com.team.dating_backend.notification.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.net.URI;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "app.notification.alert")
public class NotificationAlertProperties {

    private boolean enabled = false;

    @NotBlank
    @Size(max = 64)
    private String environment = "local";

    private String webhookUrl = "";

    @Positive
    private int poolSize = 1;

    @PositiveOrZero
    private int queueCapacity = 20;

    @PositiveOrZero
    private int shutdownWaitSeconds = 10;

    @Positive
    private int connectTimeoutMs = 2_000;

    @Positive
    private int readTimeoutMs = 3_000;

    @Positive
    private int maxMessagesPerWindow = 5;

    @Positive
    private long rateLimitWindowMs = 60_000L;

    @AssertTrue(message = "Enabled alerts require a valid Discord webhook URL")
    public boolean isWebhookValid() {
        if (!enabled) {
            return true;
        }
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return false;
        }

        try {
            URI uri = URI.create(webhookUrl);
            return "https".equalsIgnoreCase(uri.getScheme())
                && "discord.com".equalsIgnoreCase(uri.getHost())
                && (uri.getPort() == -1 || uri.getPort() == 443)
                && uri.getRawUserInfo() == null
                && uri.getRawQuery() == null
                && uri.getRawFragment() == null
                && uri.getRawPath().matches("/api(?:/v[0-9]+)?/webhooks/[0-9]+/[A-Za-z0-9._-]+");
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}

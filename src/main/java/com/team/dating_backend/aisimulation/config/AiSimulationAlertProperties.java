package com.team.dating_backend.aisimulation.config;

import com.team.dating_backend.common.alert.DiscordWebhookSender;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "app.ai-simulation.alert")
public class AiSimulationAlertProperties {

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
    private long rateLimitWindowMs = 60_000;

    @AssertTrue(message = "Enabled alerts require a valid Discord webhook URL")
    public boolean isWebhookValid() {
        return !enabled || DiscordWebhookSender.isValidWebhookUrl(webhookUrl);
    }
}

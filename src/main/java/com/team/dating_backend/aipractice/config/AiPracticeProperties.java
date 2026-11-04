package com.team.dating_backend.aipractice.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "app.ai-practice")
public class AiPracticeProperties {

    private String baseUrl = "";
    private String initialSessionPath = "/ai/api/v2/practice/start";
    private String messagePath = "/ai/api/v2/practice/%s/messages";
    private String retryPath = "/ai/api/v2/practice/%s/retry";
    private String endSessionPath = "/ai/api/v2/practice/%s/end";
    private String apiKey = "";
    private String callbackSecret = "";
    private long connectTimeoutMs = 3000;
    private long readTimeoutMs = 120000;
    private int dailyLimit = 30;
    private int maxOutboxFailures = 10;
    private long outboxPublishDelayMs = 1000;

    @Positive(message = "INTERNAL_SERVER_ERROR")
    @Max(value = Long.MAX_VALUE / 1000, message = "INTERNAL_SERVER_ERROR")
    private long outboxClaimDurationMs = 150_000;
}

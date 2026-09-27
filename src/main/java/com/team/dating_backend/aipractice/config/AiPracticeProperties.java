package com.team.dating_backend.aipractice.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.ai-practice")
public class AiPracticeProperties {

    private String baseUrl = "";
    private String initialSessionPath = "/ai/api/v1/practice/start";
    private String messagePath = "/ai/api/v1/practice/%s/messages";
    private String retryPath = "/ai/api/v1/practice/%s/retry";
    private String endSessionPath = "/ai/api/v1/practice/%s/end";
    private String apiKey = "";
    private String callbackSecret = "";
    private long connectTimeoutMs = 3000;
    private long readTimeoutMs = 120000;
    private int dailyLimit = 30;
    private int maxOutboxFailures = 10;
    private long outboxPublishDelayMs = 1000;
}

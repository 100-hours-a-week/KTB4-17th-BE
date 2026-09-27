package com.team.dating_backend.aipractice.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.ai-practice")
public class AiPracticeProperties {

    private String baseUrl = "";
    private String initialSessionPath = "/api/v1/practice/sessions";
    private String messagePath = "/api/v1/practice/sessions/%s/messages";
    private String endSessionPath = "/api/v1/practice/sessions/%s/end";
    private String apiKey = "";
    private String callbackSecret = "";
    private int dailyLimit = 30;
    private int maxOutboxFailures = 10;
    private long outboxPublishDelayMs = 1000;
}

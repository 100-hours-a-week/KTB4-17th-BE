package com.team.dating_backend.aisimulation.config;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "app.ai-simulation")
public class AiSimulationProperties {

    private String baseUrl = "";
    private String simulationPath = "/ai/api/v1/simulation";
    private int connectTimeoutMs = 3_000;
    private int readTimeoutMs = 130_000;

    @Positive
    private int asyncPoolSize = 1;

    @PositiveOrZero
    private int asyncQueueCapacity = 4;

    @PositiveOrZero
    private int asyncShutdownWaitSeconds = 30;

    @Positive
    private int maxProcessingAgeSeconds = 900;

    @AssertTrue(message = "Processing age must exceed the full queue HTTP timeout budget by at least 30 seconds")
    public boolean isProcessingAgeSufficient() {
        if (asyncPoolSize <= 0 || asyncQueueCapacity < 0) {
            return true;
        }
        long batches = ((long) asyncQueueCapacity + asyncPoolSize - 1) / asyncPoolSize + 1;
        return maxProcessingAgeSeconds * 1_000L >= batches * ((long) connectTimeoutMs + readTimeoutMs) + 30_000L;
    }
}

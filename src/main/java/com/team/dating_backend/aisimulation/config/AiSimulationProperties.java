package com.team.dating_backend.aisimulation.config;

import jakarta.validation.constraints.Positive;
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
    private String simulationPath = "/ai/api/v2/simulation";
    private int connectTimeoutMs = 3_000;
    private int readTimeoutMs = 130_000;

    @Positive
    private int asyncPoolSize = 1;

    @PositiveOrZero
    private int asyncQueueCapacity = 4;

    @PositiveOrZero
    private int asyncShutdownWaitSeconds = 30;
}

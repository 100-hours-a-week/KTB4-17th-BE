package com.team.dating_backend.aisimulation.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.ai-simulation")
public class AiSimulationProperties {

    private String baseUrl = "";
    private String simulationPath = "/ai/api/v1/simulation";
    private int connectTimeoutMs = 3_000;
    private int readTimeoutMs = 130_000;
}

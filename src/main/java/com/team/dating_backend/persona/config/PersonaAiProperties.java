package com.team.dating_backend.persona.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.persona-ai")
public class PersonaAiProperties {

    private String baseUrl = "";
    private String startPath = "/ai/api/v1/persona/onboarding/start";
    private String answerPath = "/ai/api/v1/persona/onboarding/%s/answer";
    private String skipPath = "/ai/api/v1/persona/onboarding/%s/skip";
    private String finishPath = "/ai/api/v1/persona/onboarding/%s/finish";
    private String buildPath = "/ai/api/v1/persona/%s/build";
    private String confirmPath = "/ai/api/v1/persona/%s/confirm";
}

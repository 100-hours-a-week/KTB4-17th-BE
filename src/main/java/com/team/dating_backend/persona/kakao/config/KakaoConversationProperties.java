package com.team.dating_backend.persona.kakao.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "app.persona-kakao")
public class KakaoConversationProperties {

    private String baseUrl = "";
    @NotBlank
    private String conversationPath = "/ai/api/v1/persona-extraction/conversation";
    @Positive
    @Max(5 * 1024 * 1024)
    private int maxSizeBytes = 5 * 1024 * 1024;
    @Positive
    private int connectTimeoutMs = 3000;
    @Positive
    private int readTimeoutMs = 30000;
}

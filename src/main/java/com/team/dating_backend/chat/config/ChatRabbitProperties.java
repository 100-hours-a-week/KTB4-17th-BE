package com.team.dating_backend.chat.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
// 잘못된 브로커 설정을 실제 메시지 전송 전에 발견한다.
@Validated
@ConfigurationProperties(prefix = "app.chat.rabbit")
public class ChatRabbitProperties {

    @NotBlank
    private String host = "localhost";

    @Min(1)
    private int stompPort = 61613;

    @NotBlank
    private String username = "migration";

    @NotBlank
    private String password = "migration-local";

    @NotBlank
    private String virtualHost = "/";

    @Pattern(regexp = "[A-Za-z0-9_.-]+")
    @NotBlank
    private String exchange = "ecs.chat.events";

    @Min(1)
    private long confirmTimeoutMs = 5000;
}

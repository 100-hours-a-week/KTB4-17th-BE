package com.team.dating_backend.chat.delegation.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.chat-delegation")
public class ChatDelegationProperties {

    private String baseUrl = "";
    private String apiKey = "";
}

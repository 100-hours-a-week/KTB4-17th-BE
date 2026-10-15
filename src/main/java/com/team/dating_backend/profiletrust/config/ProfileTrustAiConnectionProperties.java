package com.team.dating_backend.profiletrust.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.ai-practice")
public class ProfileTrustAiConnectionProperties {

    // Bind existing connection settings locally so this module does not depend on
    // another AI feature.
    private String baseUrl = "";
    private String apiKey = "";
}

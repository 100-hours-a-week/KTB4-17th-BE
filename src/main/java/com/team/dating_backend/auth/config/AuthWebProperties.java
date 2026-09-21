package com.team.dating_backend.auth.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.auth")
@Getter
@Setter
public class AuthWebProperties {

    private String serviceRedirectUri;
    private String onboardingRedirectUri;
    private boolean secureCookie;
    private String sameSite = "Lax";
}

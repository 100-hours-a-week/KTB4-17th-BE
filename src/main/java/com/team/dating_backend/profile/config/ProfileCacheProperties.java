package com.team.dating_backend.profile.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "app.profile-cache")
public class ProfileCacheProperties {

    private boolean enabled = true;

    @NotNull(message = "INTERNAL_SERVER_ERROR")
    @DurationMin(millis = 1, message = "INTERNAL_SERVER_ERROR")
    private Duration maxTtl = Duration.ofMinutes(3);

    @NotNull(message = "INTERNAL_SERVER_ERROR")
    @DurationMin(millis = 0, message = "INTERNAL_SERVER_ERROR")
    private Duration imageRequestBuffer = Duration.ofSeconds(10);
}

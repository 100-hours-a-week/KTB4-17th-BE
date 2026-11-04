package com.team.dating_backend.profile.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "app.profile-cache.redis")
public class ProfileCacheRedisProperties {

    @NotBlank(message = "INTERNAL_SERVER_ERROR")
    private String url = "redis://localhost:6380";

    @NotNull(message = "INTERNAL_SERVER_ERROR")
    @DurationMin(millis = 1, message = "INTERNAL_SERVER_ERROR")
    private Duration connectTimeout = Duration.ofMillis(300);

    @NotNull(message = "INTERNAL_SERVER_ERROR")
    @DurationMin(millis = 1, message = "INTERNAL_SERVER_ERROR")
    private Duration commandTimeout = Duration.ofMillis(200);
}

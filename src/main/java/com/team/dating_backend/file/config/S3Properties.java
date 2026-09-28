package com.team.dating_backend.file.config;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Validated
@ConfigurationProperties(prefix = "app.file.s3")
@Getter
@Setter
public class S3Properties {

    @NotBlank
    private String bucket;

    @NotBlank
    private String region;

    @NotNull
    private Duration uploadUrlExpiration;

    @NotNull
    private Duration downloadUrlExpiration;

    @AssertTrue
    public boolean isUrlExpirationPositive() {
        return isPositive(uploadUrlExpiration) && isPositive(downloadUrlExpiration);
    }

    private boolean isPositive(Duration duration) {
        return duration != null && !duration.isNegative() && !duration.isZero();
    }
}

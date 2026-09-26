package com.team.dating_backend.file.config;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.AssertTrue;
import java.time.Duration;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.file")
@Getter
@Setter
public class FileProperties {

    @Positive
    private long maxSizeBytes;

    @NotEmpty
    private List<String> allowedMimeTypes;

    @NotNull
    private Duration uploadIntentTtl;

    @NotNull
    private Duration uploadIntentRetention;

    @Positive
    private long cleanupDelayMs;

    @Positive
    private int cleanupBatchSize;

    @AssertTrue
    public boolean isDurationConfigurationPositive() {
        return isPositive(uploadIntentTtl) && isPositive(uploadIntentRetention);
    }

    private boolean isPositive(Duration duration) {
        return duration != null && !duration.isNegative() && !duration.isZero();
    }
}

package com.team.dating_backend.file.config;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.file.s3")
@Getter
@Setter
public class S3Properties {

    private String bucket;
    private String region;
    private Duration presignedUrlExpiration = Duration.ofMinutes(10);
}

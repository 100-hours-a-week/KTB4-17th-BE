package com.team.dating_backend.auth.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "jwt")
@Getter
@Setter
public class JwtProperties {

  private String secret;
  private long pendingExpirationMinutes;
  private long serviceExpirationMinutes;
}

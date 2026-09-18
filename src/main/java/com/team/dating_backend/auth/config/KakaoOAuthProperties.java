package com.team.dating_backend.auth.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "kakao.oauth")
@Getter
@Setter
public class KakaoOAuthProperties {

  private String clientId;
  private String clientSecret;
  private String redirectUri;
  private String authorizationUri;
  private String tokenUri;
  private String userInfoUri;
}

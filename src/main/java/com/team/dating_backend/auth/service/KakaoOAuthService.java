package com.team.dating_backend.auth.service;

import java.util.UUID;
import com.team.dating_backend.auth.config.KakaoOAuthProperties;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

@Service
@RequiredArgsConstructor
public class KakaoOAuthService {

    private static final String SESSION_STATE_KEY = "KAKAO_SESSION_STATE";
    private static final String SESSION_STATE_CREATED_AT_KEY ="KAKAO_OAUTH_SESSION_CREATED_AT";
    private final KakaoOAuthProperties kakaoOAuthProperties;

    public String createAuthorizationUrl(HttpSession session) {
        String state = UUID.randomUUID().toString();

        session.setAttribute(SESSION_STATE_KEY, state);
        session.setAttribute(SESSION_STATE_CREATED_AT_KEY, System.currentTimeMillis());

        String authorizationUrl = UriComponentsBuilder
                .fromUriString(kakaoOAuthProperties.getAuthorizationUri())
                .queryParam("response_type", "code")
                .queryParam("client_id", kakaoOAuthProperties.getClientId())
                .queryParam("redirect_uri", kakaoOAuthProperties.getRedirectUri())
                .queryParam("state", state)
                .build()
                .encode()
                .toUriString();
        return authorizationUrl;
    }
}

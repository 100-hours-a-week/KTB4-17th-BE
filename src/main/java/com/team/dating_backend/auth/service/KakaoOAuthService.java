package com.team.dating_backend.auth.service;

import com.team.dating_backend.auth.config.KakaoOAuthProperties;
import com.team.dating_backend.auth.dto.KakaoTokenResponse;
import com.team.dating_backend.auth.dto.KakaoUserInfoResponse;
import jakarta.servlet.http.HttpSession;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@Service
@RequiredArgsConstructor
public class KakaoOAuthService {

    private static final long STATE_TTL_MILLIS = 5 * 60 * 1000L;
    private static final String SESSION_STATE_KEY = "KAKAO_SESSION_STATE";
    private static final String SESSION_STATE_CREATED_AT_KEY = "KAKAO_OAUTH_SESSION_CREATED_AT";
    private final KakaoOAuthProperties kakaoOAuthProperties;
    private final RestClient.Builder restClientBuilder;

    public String createAuthorizationUrl(HttpSession session) {

        String state = UUID.randomUUID().toString();

        session.setAttribute(SESSION_STATE_KEY, state);
        session.setAttribute(SESSION_STATE_CREATED_AT_KEY, System.currentTimeMillis());

        String authorizationUrl =
                UriComponentsBuilder.fromUriString(kakaoOAuthProperties.getAuthorizationUri())
                        .queryParam("response_type", "code")
                        .queryParam("client_id", kakaoOAuthProperties.getClientId())
                        .queryParam("redirect_uri", kakaoOAuthProperties.getRedirectUri())
                        .queryParam("state", state)
                        .build()
                        .encode()
                        .toUriString();
        return authorizationUrl;
    }

    public boolean validateState(String receivedState, HttpSession session) {

        String savedState = (String) session.getAttribute(SESSION_STATE_KEY);
        Long createdAt = (Long) session.getAttribute(SESSION_STATE_CREATED_AT_KEY);

        if (savedState == null || createdAt == null) {
            return false;
        }

        boolean stateMatches = Objects.equals(savedState, receivedState);

        long elapsedTime = System.currentTimeMillis() - createdAt;
        boolean stateNotExpired = elapsedTime >= 0 && elapsedTime <= STATE_TTL_MILLIS;

        if (!stateMatches || !stateNotExpired) {
            return false;
        }

        session.removeAttribute(SESSION_STATE_KEY);
        session.removeAttribute(SESSION_STATE_CREATED_AT_KEY);

        return true;
    }

    public KakaoTokenResponse requestAccessToken(String code) {

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", kakaoOAuthProperties.getClientId());
        form.add("redirect_uri", kakaoOAuthProperties.getRedirectUri());
        form.add("code", code);
        form.add("client_secret", kakaoOAuthProperties.getClientSecret());

        return restClientBuilder
                .build()
                .post()
                .uri(kakaoOAuthProperties.getTokenUri())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(KakaoTokenResponse.class);
    }

    public KakaoUserInfoResponse requestUserInfo(String accessToken) {

        return restClientBuilder
                .build()
                .get()
                .uri(kakaoOAuthProperties.getUserInfoUri())
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .body(KakaoUserInfoResponse.class);
    }
}

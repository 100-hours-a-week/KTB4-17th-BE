package com.team.dating_backend.auth.service;

import com.team.dating_backend.auth.config.KakaoOAuthProperties;
import com.team.dating_backend.auth.dto.KakaoTokenResponse;
import com.team.dating_backend.auth.dto.KakaoUserInfoResponse;
import com.team.dating_backend.auth.dto.OAuthIdentity;
import com.team.dating_backend.auth.enums.AuthProvider;
import com.team.dating_backend.auth.exception.OAuthProviderUnavailableException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

@Service
@RequiredArgsConstructor
public class KakaoOAuthClient implements OAuthProviderClient {

    private final KakaoOAuthProperties kakaoOAuthProperties;
    private final RestClient.Builder restClientBuilder;

    @Override
    public AuthProvider provider() {
        return AuthProvider.KAKAO;
    }

    @Override
    public String createAuthorizationUrl(String state) {
        return UriComponentsBuilder.fromUriString(kakaoOAuthProperties.getAuthorizationUri())
                .queryParam("response_type", "code")
                .queryParam("client_id", kakaoOAuthProperties.getClientId())
                .queryParam("redirect_uri", kakaoOAuthProperties.getRedirectUri())
                .queryParam("state", state)
                .build()
                .encode()
                .toUriString();
    }

    @Override
    public OAuthIdentity requestIdentity(String authorizationCode) {
        try {
            KakaoTokenResponse tokenResponse = requestAccessToken(authorizationCode);

            if (tokenResponse == null || !StringUtils.hasText(tokenResponse.accessToken())) {
                throw new OAuthProviderUnavailableException();
            }

            KakaoUserInfoResponse userInfo = requestUserInfo(tokenResponse.accessToken());

            if (userInfo == null || userInfo.id() == null) {
                throw new OAuthProviderUnavailableException();
            }

            return new OAuthIdentity(AuthProvider.KAKAO, String.valueOf(userInfo.id()));
        } catch (RestClientException exception) {
            throw new OAuthProviderUnavailableException(exception);
        }
    }

    private KakaoTokenResponse requestAccessToken(String authorizationCode) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", kakaoOAuthProperties.getClientId());
        form.add("redirect_uri", kakaoOAuthProperties.getRedirectUri());
        form.add("code", authorizationCode);
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

    private KakaoUserInfoResponse requestUserInfo(String accessToken) {
        return restClientBuilder
                .build()
                .get()
                .uri(kakaoOAuthProperties.getUserInfoUri())
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .body(KakaoUserInfoResponse.class);
    }
}

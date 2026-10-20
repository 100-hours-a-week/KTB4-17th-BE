package com.team.dating_backend.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.team.dating_backend.auth.config.KakaoOAuthProperties;
import com.team.dating_backend.auth.dto.OAuthIdentity;
import com.team.dating_backend.auth.enums.AuthProvider;
import com.team.dating_backend.auth.exception.KakaoTalkNicknameRequiredException;
import com.team.dating_backend.auth.exception.KakaoTalkNicknameTooLongException;
import com.team.dating_backend.auth.exception.OAuthProviderUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class KakaoOAuthClientTest {

    private MockRestServiceServer server;
    private KakaoOAuthClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        KakaoOAuthProperties properties = new KakaoOAuthProperties();
        properties.setClientId("client-id");
        properties.setClientSecret("client-secret");
        properties.setRedirectUri("https://backend.example.com/api/v1/auth/kakao/callback");
        properties.setAuthorizationUri("https://kauth.kakao.com/oauth/authorize");
        properties.setTokenUri("https://kauth.kakao.com/oauth/token");
        properties.setUserInfoUri("https://kapi.kakao.com/v2/user/me");
        properties.setTalkProfileUri("https://kapi.kakao.com/v1/api/talk/profile");
        client = new KakaoOAuthClient(properties, builder);
    }

    @Test
    void 카카오_인가_URL에_프로필_닉네임_동의_항목을_포함한다() {
        assertThat(client.createAuthorizationUrl("state-value"))
            .contains("scope=profile_nickname", "state=state-value");
    }

    @ParameterizedTest
    @ValueSource(strings = {"홍길동", "Alice"})
    void 한글_또는_영어_카카오톡_프로필_닉네임을_조회한다(String nickname) {
        expectTokenAndUserInfo();
        expectTalkProfile("{\"id\":123,\"nickName\":\"" + nickname + "\"}");

        OAuthIdentity identity = client.requestIdentity("authorization-code");

        assertThat(identity.provider()).isEqualTo(AuthProvider.KAKAO);
        assertThat(identity.providerUserId()).isEqualTo("123");
        assertThat(identity.kakaoTalkNickname()).isEqualTo(nickname);
        server.verify();
    }

    @Test
    void 두_카카오_API의_사용자_ID가_다르면_로그인에_실패한다() {
        expectTokenAndUserInfo();
        expectTalkProfile("{\"id\":456,\"nickName\":\"홍길동\"}");

        assertThatThrownBy(() -> client.requestIdentity("authorization-code"))
            .isInstanceOf(OAuthProviderUnavailableException.class);
        server.verify();
    }

    @Test
    void 카카오톡_닉네임이_비어_있으면_로그인에_실패한다() {
        expectTokenAndUserInfo();
        expectTalkProfile("{\"id\":123,\"nickName\":\" \"}");

        assertThatThrownBy(() -> client.requestIdentity("authorization-code"))
            .isInstanceOf(KakaoTalkNicknameRequiredException.class);
        server.verify();
    }

    @Test
    void 카카오톡_닉네임이_21자를_넘으면_로그인에_실패한다() {
        expectTokenAndUserInfo();
        expectTalkProfile("{\"id\":123,\"nickName\":\"" + "A".repeat(22) + "\"}");

        assertThatThrownBy(() -> client.requestIdentity("authorization-code"))
            .isInstanceOf(KakaoTalkNicknameTooLongException.class);
        server.verify();
    }

    private void expectTokenAndUserInfo() {
        server.expect(requestTo("https://kauth.kakao.com/oauth/token"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(
                "{\"access_token\":\"kakao-access-token\",\"token_type\":\"bearer\",\"expires_in\":3600}",
                MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://kapi.kakao.com/v2/user/me"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bearer kakao-access-token"))
            .andRespond(withSuccess("{\"id\":123}", MediaType.APPLICATION_JSON));
    }

    private void expectTalkProfile(String response) {
        server.expect(requestTo("https://kapi.kakao.com/v1/api/talk/profile"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bearer kakao-access-token"))
            .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
    }
}

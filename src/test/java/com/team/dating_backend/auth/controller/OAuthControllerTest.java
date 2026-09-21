package com.team.dating_backend.auth.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.team.dating_backend.auth.dto.OAuthIdentity;
import com.team.dating_backend.auth.dto.SocialLoginResult;
import com.team.dating_backend.auth.enums.AuthProvider;
import com.team.dating_backend.auth.enums.LoginDestination;
import com.team.dating_backend.auth.exception.OAuthProviderUnavailableException;
import com.team.dating_backend.auth.service.OAuthProviderClient;
import com.team.dating_backend.auth.service.OAuthProviderClientRegistry;
import com.team.dating_backend.auth.service.OAuthStateService;
import com.team.dating_backend.auth.service.SocialLoginService;
import jakarta.servlet.http.HttpSession;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(OAuthController.class)
class OAuthControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private OAuthProviderClientRegistry oauthProviderClientRegistry;

    @MockitoBean private OAuthStateService oauthStateService;

    @MockitoBean private SocialLoginService socialLoginService;

    @MockitoBean private AuthLoginResponseFactory authLoginResponseFactory;

    @Test
    void 카카오_로그인_시작은_state를_만들고_카카오_인가_URL로_리다이렉트한다() throws Exception {
        // given
        OAuthProviderClient kakaoClient = mock(OAuthProviderClient.class);
        given(oauthProviderClientRegistry.get(AuthProvider.KAKAO)).willReturn(kakaoClient);
        given(oauthStateService.createState(eq(AuthProvider.KAKAO), any(HttpSession.class)))
                .willReturn("state-value");
        given(kakaoClient.createAuthorizationUrl("state-value"))
                .willReturn("https://kauth.kakao.com/oauth/authorize?state=state-value");

        // when & then
        mockMvc.perform(get("/api/v1/auth/kakao"))
                .andExpect(status().isFound())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(
                        header().string(
                                        "Location",
                                        "https://kauth.kakao.com/oauth/authorize?state=state-value"));

        verify(oauthProviderClientRegistry).get(AuthProvider.KAKAO);
        verify(authLoginResponseFactory).validateRedirectUris();
        verify(oauthStateService).createState(eq(AuthProvider.KAKAO), any(HttpSession.class));
        verify(kakaoClient).createAuthorizationUrl("state-value");
    }

    @Test
    void 콜백에_code가_없으면_INVALID_REQUEST를_반환하고_외부_API를_호출하지_않는다() throws Exception {
        // when & then
        mockMvc.perform(get("/api/v1/auth/kakao/callback").param("state", "state-value"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));

        verifyNoInteractions(
                oauthProviderClientRegistry,
                oauthStateService,
                socialLoginService,
                authLoginResponseFactory);
    }

    @Test
    void 카카오_콜백은_공통_로그인_결과를_HTTP_응답으로_변환한다() throws Exception {
        // given
        OAuthProviderClient kakaoClient = mock(OAuthProviderClient.class);
        OAuthIdentity identity = new OAuthIdentity(AuthProvider.KAKAO, "kakao-123");
        SocialLoginResult loginResult =
                new SocialLoginResult.Authenticated(1L, LoginDestination.SERVICE);
        ResponseEntity<Void> loginResponse =
                ResponseEntity.status(HttpStatus.FOUND)
                        .location(URI.create("https://frontend.example.com/service"))
                        .build();

        given(oauthProviderClientRegistry.get(AuthProvider.KAKAO)).willReturn(kakaoClient);
        given(kakaoClient.requestIdentity("authorization-code")).willReturn(identity);
        given(socialLoginService.login(AuthProvider.KAKAO, "kakao-123")).willReturn(loginResult);
        given(authLoginResponseFactory.create(loginResult)).willReturn(loginResponse);

        // when & then
        mockMvc.perform(
                        get("/api/v1/auth/kakao/callback")
                                .param("code", "authorization-code")
                                .param("state", "state-value"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://frontend.example.com/service"));

        verify(oauthStateService)
                .validateAndConsumeState(
                        eq(AuthProvider.KAKAO), eq("state-value"), any(HttpSession.class));
        verify(kakaoClient).requestIdentity("authorization-code");
        verify(socialLoginService).login(AuthProvider.KAKAO, "kakao-123");
        verify(authLoginResponseFactory).validateRedirectUris();
        verify(authLoginResponseFactory).create(loginResult);
    }

    @Test
    void 카카오_사용자_정보_조회에_실패하면_AUTH_PROVIDER_UNAVAILABLE을_반환한다() throws Exception {
        // given
        OAuthProviderClient kakaoClient = mock(OAuthProviderClient.class);
        given(oauthProviderClientRegistry.get(AuthProvider.KAKAO)).willReturn(kakaoClient);
        willThrow(new OAuthProviderUnavailableException())
                .given(kakaoClient)
                .requestIdentity("authorization-code");

        // when & then
        mockMvc.perform(
                        get("/api/v1/auth/kakao/callback")
                                .param("code", "authorization-code")
                                .param("state", "state-value"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.errorCode").value("AUTH_PROVIDER_UNAVAILABLE"));

        verify(oauthStateService)
                .validateAndConsumeState(
                        eq(AuthProvider.KAKAO), eq("state-value"), any(HttpSession.class));
        verify(kakaoClient).requestIdentity("authorization-code");
        verify(authLoginResponseFactory).validateRedirectUris();
        verifyNoInteractions(socialLoginService);
    }
}

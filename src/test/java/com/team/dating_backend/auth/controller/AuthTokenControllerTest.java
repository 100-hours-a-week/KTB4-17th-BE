package com.team.dating_backend.auth.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.team.dating_backend.auth.dto.IssuedAuthTokens;
import com.team.dating_backend.auth.service.OAuthLoginCodeService;
import jakarta.servlet.http.HttpSession;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AuthTokenController.class)
class AuthTokenControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OAuthLoginCodeService oauthLoginCodeService;

    @MockitoBean
    private AuthCookieFactory authCookieFactory;

    @Test
    void 유효한_로그인_코드를_교환하면_Bearer_토큰을_캐시하지_않고_반환한다()
        throws Exception {
        MockHttpSession session = new MockHttpSession();
        given(oauthLoginCodeService.consume(eq("one-time-code"), any(HttpSession.class)))
            .willReturn(Optional.of(new IssuedAuthTokens("service-token", "refresh-token")));
        given(authCookieFactory.refreshToken("refresh-token"))
            .willReturn(
                org.springframework.http.ResponseCookie.from("REFRESH_TOKEN", "refresh-token")
                    .httpOnly(true)
                    .path("/api/v1/auth")
                    .build());

        mockMvc.perform(
            post("/api/v1/auth/token/exchange")
                .session(session)
                .contentType("application/json")
                .content("{\"code\":\"one-time-code\"}"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("Pragma", "no-cache"))
            .andExpect(header().string("Set-Cookie",
                org.hamcrest.Matchers.containsString("REFRESH_TOKEN=refresh-token")))
            .andExpect(jsonPath("$.accessToken").value("service-token"))
            .andExpect(jsonPath("$.tokenType").value("Bearer"));
    }

    @Test
    void 세션에_묶이지_않은_코드는_AUTH_REQUIRED로_거부한다() throws Exception {
        given(oauthLoginCodeService.consume("one-time-code", null)).willReturn(Optional.empty());

        mockMvc.perform(
            post("/api/v1/auth/token/exchange")
                .contentType("application/json")
                .content("{\"code\":\"one-time-code\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.errorCode").value("AUTH_REQUIRED"));
    }
}

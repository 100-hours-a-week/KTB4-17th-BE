package com.team.dating_backend.auth.controller;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.team.dating_backend.notification.sse.NotificationEmitterRegistry;
import com.team.dating_backend.notification.sse.NotificationSseObservation;
import com.team.dating_backend.auth.dto.IssuedAuthTokens;
import com.team.dating_backend.auth.enums.AuthErrorCode;
import com.team.dating_backend.auth.service.AuthTokenService;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.ResponseCookie;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@WebMvcTest(AuthSessionController.class)
class AuthSessionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NotificationEmitterRegistry notificationEmitterRegistry;

    @MockitoBean
    private NotificationSseObservation notificationSseObservation;

    @MockitoBean
    private AuthCookieFactory authCookieFactory;

    @MockitoBean
    private AuthTokenService authTokenService;

    @Test
    void 유효한_Refresh_쿠키는_Bearer_Access_Token_JSON으로_교환된다() throws Exception {
        Instant refreshExpiresAt = Instant.now().plusSeconds(3600);
        given(authTokenService.rotate("refresh-token"))
            .willReturn(new IssuedAuthTokens(
                "new-access-token", "new-refresh-token", refreshExpiresAt));
        given(authCookieFactory.refreshToken("new-refresh-token", refreshExpiresAt))
            .willReturn(ResponseCookie.from("REFRESH_TOKEN", "new-refresh-token")
                .httpOnly(true)
                .path("/api/v1/auth")
                .build());

        mockMvc.perform(
            post("/api/v1/auth/token/refresh")
                .cookie(new Cookie("REFRESH_TOKEN", "refresh-token")))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("Pragma", "no-cache"))
            .andExpect(jsonPath("$.accessToken").value("new-access-token"))
            .andExpect(jsonPath("$.tokenType").value("Bearer"))
            .andExpect(header().string("Set-Cookie",
                org.hamcrest.Matchers.containsString("REFRESH_TOKEN=new-refresh-token")));
    }

    @Test
    void 유효하지_않은_Refresh_쿠키는_AUTH_REQUIRED와_쿠키_삭제를_반환한다() throws Exception {
        given(authTokenService.rotate("invalid-token"))
            .willThrow(new JwtException("Invalid refresh token"));
        given(authCookieFactory.deleteAccessToken())
            .willReturn(ResponseCookie.from("ACCESS_TOKEN", "").maxAge(0).build());
        given(authCookieFactory.deleteRefreshToken())
            .willReturn(ResponseCookie.from("REFRESH_TOKEN", "").maxAge(0).build());

        MvcResult result = mockMvc.perform(
            post("/api/v1/auth/token/refresh")
                .cookie(new Cookie("REFRESH_TOKEN", "invalid-token")))
            .andExpect(status().isUnauthorized())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.errorCode").value("AUTH_REQUIRED"))
            .andReturn();

        List<String> setCookies = result.getResponse()
            .getHeaders(org.springframework.http.HttpHeaders.SET_COOKIE);
        assertTrue(setCookies.stream().anyMatch(cookie -> cookie.contains("ACCESS_TOKEN=")));
        assertTrue(setCookies.stream().anyMatch(cookie -> cookie.contains("REFRESH_TOKEN=")));
    }

    @Test
    void Refresh_쿠키가_없으면_AUTH_REQUIRED를_반환하고_인증_쿠키를_삭제한다() throws Exception {
        given(authTokenService.rotate(null))
            .willThrow(new JwtException(AuthErrorCode.REFRESH_AUTH_TOKEN_MISSING.name()));
        given(authCookieFactory.deleteAccessToken())
            .willReturn(ResponseCookie.from("ACCESS_TOKEN", "").maxAge(0).build());
        given(authCookieFactory.deleteRefreshToken())
            .willReturn(ResponseCookie.from("REFRESH_TOKEN", "").maxAge(0).build());

        MvcResult result = mockMvc.perform(post("/api/v1/auth/token/refresh"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.errorCode").value("AUTH_REQUIRED"))
            .andReturn();

        List<String> setCookies = result.getResponse()
            .getHeaders(org.springframework.http.HttpHeaders.SET_COOKIE);
        assertTrue(setCookies.stream().anyMatch(cookie -> cookie.contains("ACCESS_TOKEN=")));
        assertTrue(setCookies.stream().anyMatch(cookie -> cookie.contains("REFRESH_TOKEN=")));
    }

    @Test
    void 탈퇴한_사용자의_Refresh_쿠키는_AUTH_REQUIRED를_반환한다() throws Exception {
        given(authTokenService.rotate("refresh-token"))
            .willThrow(new JwtException(AuthErrorCode.REFRESH_TOKEN_USER_NOT_ALLOWED.name()));
        given(authCookieFactory.deleteAccessToken())
            .willReturn(ResponseCookie.from("ACCESS_TOKEN", "").maxAge(0).build());
        given(authCookieFactory.deleteRefreshToken())
            .willReturn(ResponseCookie.from("REFRESH_TOKEN", "").maxAge(0).build());

        mockMvc.perform(post("/api/v1/auth/token/refresh")
            .cookie(new Cookie("REFRESH_TOKEN", "refresh-token")))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.errorCode").value("AUTH_REQUIRED"));
    }

    @Test
    void 로그아웃_시_Refresh_쿠키의_현재_세션을_폐기하고_쿠키를_삭제한다() throws Exception {
        given(authCookieFactory.deleteAccessToken())
            .willReturn(ResponseCookie.from("ACCESS_TOKEN", "").maxAge(0).build());
        given(authCookieFactory.deleteRefreshToken())
            .willReturn(ResponseCookie.from("REFRESH_TOKEN", "").maxAge(0).build());
        given(authCookieFactory.deletePendingRegistrationToken())
            .willReturn(ResponseCookie.from("PENDING_REGISTRATION_TOKEN", "")
                .maxAge(0)
                .build());

        mockMvc.perform(post("/api/v1/auth/logout")
            .cookie(new Cookie("REFRESH_TOKEN", "refresh-token")))
            .andExpect(status().isNoContent());

        verify(authTokenService).revokeCurrent("refresh-token");
    }

    @Test
    void Refresh_쿠키가_없어도_로그아웃은_성공한다() throws Exception {
        given(authCookieFactory.deleteAccessToken())
            .willReturn(ResponseCookie.from("ACCESS_TOKEN", "").maxAge(0).build());
        given(authCookieFactory.deleteRefreshToken())
            .willReturn(ResponseCookie.from("REFRESH_TOKEN", "").maxAge(0).build());
        given(authCookieFactory.deletePendingRegistrationToken())
            .willReturn(ResponseCookie.from("PENDING_REGISTRATION_TOKEN", "")
                .maxAge(0)
                .build());

        mockMvc.perform(post("/api/v1/auth/logout"))
            .andExpect(status().isNoContent());

        verify(authTokenService).revokeCurrent(null);
    }
}

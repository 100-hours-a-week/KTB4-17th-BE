package com.team.dating_backend.auth.controller;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.team.dating_backend.auth.service.JwtService;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.Cookie;
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
    private AuthCookieFactory authCookieFactory;

    @MockitoBean
    private JwtService jwtService;

    @Test
    void 유효한_Refresh_쿠키는_Bearer_Access_Token_JSON으로_교환된다() throws Exception {
        given(jwtService.parseRefreshAuthToken("refresh-token")).willReturn(123L);
        given(jwtService.createServiceAuthToken(123L)).willReturn("new-access-token");

        mockMvc.perform(
            post("/api/v1/auth/token/refresh")
                .cookie(new Cookie("REFRESH_TOKEN", "refresh-token")))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("Pragma", "no-cache"))
            .andExpect(jsonPath("$.accessToken").value("new-access-token"))
            .andExpect(jsonPath("$.tokenType").value("Bearer"))
            .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    void 유효하지_않은_Refresh_쿠키는_AUTH_REQUIRED와_쿠키_삭제를_반환한다() throws Exception {
        given(jwtService.parseRefreshAuthToken("invalid-token"))
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
}

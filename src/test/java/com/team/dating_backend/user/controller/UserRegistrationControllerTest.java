package com.team.dating_backend.user.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.team.dating_backend.auth.controller.AuthCookieFactory;
import com.team.dating_backend.auth.exception.PendingRegistrationAccessDeniedException;
import com.team.dating_backend.user.dto.request.UserRegistrationRequest;
import com.team.dating_backend.user.service.UserRegistrationService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(UserRegistrationController.class)
class UserRegistrationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserRegistrationService userRegistrationService;

    @MockitoBean
    private AuthCookieFactory authCookieFactory;

    @Test
    void 재사용된_Pending_토큰은_AUTH_REQUIRED를_반환한다() throws Exception {
        // given
        given(
            userRegistrationService.registerUser(
                eq("stale-pending"), any(UserRegistrationRequest.class)))
            .willThrow(new PendingRegistrationAccessDeniedException());

        // when & then
        mockMvc.perform(
            put("/api/v1/registration/identity")
                .cookie(new Cookie("PENDING_REGISTRATION_TOKEN", "stale-pending"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                        {
                          "name": "이안",
                          "birthDate": "2000-01-01",
                          "gender": "MALE"
                        }
                        """))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.errorCode").value("AUTH_REQUIRED"));
    }

    @Test
    void 회원가입_완료는_Bearer_토큰을_JSON으로_반환하고_기존_인증_쿠키를_삭제한다()
        throws Exception {
        given(
            userRegistrationService.registerUser(
                eq("pending-token"), any(UserRegistrationRequest.class)))
            .willReturn("new-service-token");
        given(authCookieFactory.deleteAccessToken())
            .willReturn(ResponseCookie.from("ACCESS_TOKEN", "").maxAge(0).build());
        given(authCookieFactory.deletePendingRegistrationToken())
            .willReturn(ResponseCookie.from("PENDING_REGISTRATION_TOKEN", "")
                .maxAge(0)
                .build());

        mockMvc.perform(
            put("/api/v1/registration/identity")
                .cookie(new Cookie("PENDING_REGISTRATION_TOKEN", "pending-token"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                        {
                          "name": "이안",
                          "birthDate": "2000-01-01",
                          "gender": "MALE"
                        }
                        """))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.accessToken").value("new-service-token"))
            .andExpect(jsonPath("$.tokenType").value("Bearer"));
    }
}

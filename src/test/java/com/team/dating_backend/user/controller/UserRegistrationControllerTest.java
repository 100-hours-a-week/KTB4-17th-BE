package com.team.dating_backend.user.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
                          "name": "우",
                          "birthDate": "2000-01-01",
                          "gender": "MALE"
                        }
                        """))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.errorCode").value("AUTH_REQUIRED"));
    }
}

package com.team.dating_backend.user.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.team.dating_backend.auth.controller.AuthCookieFactory;
import com.team.dating_backend.auth.exception.PendingOnboardingAccessDeniedException;
import com.team.dating_backend.user.dto.request.OnboardingIdentityRequest;
import com.team.dating_backend.user.service.OnboardingService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(OnboardingController.class)
class OnboardingControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private OnboardingService onboardingService;

    @MockitoBean private AuthCookieFactory authCookieFactory;

    @Test
    void 재사용된_Pending_토큰은_AUTH_REQUIRED를_반환한다() throws Exception {
        // given
        given(
                        onboardingService.saveIdentity(
                                eq("stale-pending"), any(OnboardingIdentityRequest.class)))
                .willThrow(new PendingOnboardingAccessDeniedException());

        // when & then
        mockMvc.perform(
                        put("/api/v1/onboarding/identity")
                                .cookie(new Cookie("PENDING_ONBOARDING_TOKEN", "stale-pending"))
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

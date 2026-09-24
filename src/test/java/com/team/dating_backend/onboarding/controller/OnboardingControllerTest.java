package com.team.dating_backend.onboarding.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.team.dating_backend.auth.controller.AuthCookieFactory;
import com.team.dating_backend.auth.service.JwtService;
import com.team.dating_backend.onboarding.dto.response.OnboardingProfileResponse;
import com.team.dating_backend.onboarding.dto.response.OnboardingRequirementsResponse;
import com.team.dating_backend.onboarding.dto.response.OnboardingStatusResponse;
import com.team.dating_backend.onboarding.enums.OnboardingStep;
import com.team.dating_backend.onboarding.service.OnboardingService;
import com.team.dating_backend.security.ApiAccessDeniedHandler;
import com.team.dating_backend.security.ApiAuthenticationEntryPoint;
import com.team.dating_backend.security.config.SecurityConfig;
import com.team.dating_backend.security.config.SecurityProperties;
import com.team.dating_backend.user.enums.Gender;
import com.team.dating_backend.user.enums.UserStatus;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(OnboardingController.class)
@Import({SecurityConfig.class, ApiAuthenticationEntryPoint.class, ApiAccessDeniedHandler.class})
class OnboardingControllerTest {

    private static final Long USER_ID = 1L;
    private static final String ACCESS_TOKEN = "service-token";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OnboardingService onboardingService;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private SecurityProperties securityProperties;

    @BeforeEach
    void setUp() {
        given(jwtService.parseServiceAuthToken(ACCESS_TOKEN)).willReturn(USER_ID);
        given(securityProperties.getAllowedOrigins()).willReturn(List.of());
    }

    @Test
    void 온보딩_진행_상태를_조회하면_200과_그에_대한_응답을_반환한다() throws Exception {
        OnboardingRequirementsResponse requirements = new OnboardingRequirementsResponse(true, true, false, false,
            false);
        given(onboardingService.getOnboardingStatus(USER_ID))
            .willReturn(
                new OnboardingStatusResponse(
                    UserStatus.ONBOARDING, OnboardingStep.PROFILE, requirements));

        mockMvc.perform(
            get("/api/v1/users/me/onboarding")
                .cookie(
                    new Cookie(
                        AuthCookieFactory.ACCESS_TOKEN_COOKIE,
                        ACCESS_TOKEN)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("onboarding_status_get_success"))
            .andExpect(jsonPath("$.data.userStatus").value("ONBOARDING"))
            .andExpect(jsonPath("$.data.onboardingNextStep").value("PROFILE"))
            .andExpect(jsonPath("$.data.requirements.nicknameComplete").value(true))
            .andExpect(jsonPath("$.data.requirements.regionComplete").value(true))
            .andExpect(jsonPath("$.data.requirements.basicInfoComplete").value(false))
            .andExpect(jsonPath("$.data.requirements.lifestyleComplete").value(false))
            .andExpect(jsonPath("$.data.requirements.mbtiComplete").value(false));

        verify(onboardingService).getOnboardingStatus(USER_ID);
    }

    @Test
    void 온보딩_프로필을_조회하면_200과_그에_대한_응답을_반환한다() throws Exception {
        LocalDate birthDate = LocalDate.of(1990, 5, 21);
        given(onboardingService.getOnboardingProfile(USER_ID))
            .willReturn(new OnboardingProfileResponse(USER_ID, birthDate, Gender.FEMALE));

        mockMvc.perform(
            get("/api/v1/users/me/onboarding/profile")
                .cookie(
                    new Cookie(
                        AuthCookieFactory.ACCESS_TOKEN_COOKIE,
                        ACCESS_TOKEN)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("user_onboarding_profile_get_success"))
            .andExpect(jsonPath("$.data.userId").value(USER_ID))
            .andExpect(jsonPath("$.data.birthDate").value("1990-05-21"))
            .andExpect(jsonPath("$.data.gender").value("FEMALE"));

        verify(onboardingService).getOnboardingProfile(USER_ID);
    }
}

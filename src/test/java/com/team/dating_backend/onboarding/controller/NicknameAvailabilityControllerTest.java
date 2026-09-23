package com.team.dating_backend.onboarding.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.team.dating_backend.onboarding.dto.response.NicknameAvailabilityResponse;
import com.team.dating_backend.onboarding.service.NicknameAvailabilityService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(NicknameAvailabilityController.class)
class NicknameAvailabilityControllerTest {

    private static final String NICKNAME = "하리";
    private static final String VALIDATION_REASON =
            "must be 2 to 10 characters using Korean, English letters, or digits";

    @Autowired private MockMvc mockMvc;

    @MockitoBean private NicknameAvailabilityService nicknameAvailabilityService;

    @Test
    void 사용_가능한_닉네임이면_200과_true를_반환한다() throws Exception {
        given(nicknameAvailabilityService.checkNicknameAvailability(NICKNAME))
                .willReturn(new NicknameAvailabilityResponse(true));

        mockMvc.perform(get("/api/v1/nicknames/availability").param("nickname", NICKNAME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("nickname_availability_check_success"))
                .andExpect(jsonPath("$.data.available").value(true));

        verify(nicknameAvailabilityService).checkNicknameAvailability(NICKNAME);
    }

    @Test
    void 중복된_닉네임이면_200과_false를_반환한다() throws Exception {
        given(nicknameAvailabilityService.checkNicknameAvailability(NICKNAME))
                .willReturn(new NicknameAvailabilityResponse(false));

        mockMvc.perform(get("/api/v1/nicknames/availability").param("nickname", NICKNAME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("nickname_availability_check_success"))
                .andExpect(jsonPath("$.data.available").value(false));

        verify(nicknameAvailabilityService).checkNicknameAvailability(NICKNAME);
    }

    @Test
    void 닉네임이_누락되면_400과_검증_오류를_반환한다() throws Exception {
        mockMvc.perform(get("/api/v1/nicknames/availability"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("nickname"))
                .andExpect(jsonPath("$.errors[0].reason").value(VALIDATION_REASON));

        verifyNoInteractions(nicknameAvailabilityService);
    }

    @ParameterizedTest
    @EmptySource
    @ValueSource(strings = {"한", "12345678901", " ", "닉네임!", "nick name"})
    void 닉네임_형식이_올바르지_않으면_400과_검증_오류를_반환한다(String nickname) throws Exception {
        mockMvc.perform(get("/api/v1/nicknames/availability").param("nickname", nickname))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("nickname"))
                .andExpect(jsonPath("$.errors[0].reason").value(VALIDATION_REASON));

        verifyNoInteractions(nicknameAvailabilityService);
    }
}

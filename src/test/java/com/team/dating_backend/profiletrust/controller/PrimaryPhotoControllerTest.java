package com.team.dating_backend.profiletrust.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.team.dating_backend.notification.sse.NotificationEmitterRegistry;
import com.team.dating_backend.notification.sse.NotificationSseObservation;
import com.team.dating_backend.profiletrust.dto.PrimaryPhotoCheckResult;
import com.team.dating_backend.profiletrust.service.PrimaryPhotoCheckService;
import com.team.dating_backend.security.ServiceAuthenticationPrincipal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
    controllers = PrimaryPhotoController.class, excludeAutoConfiguration = {
        SecurityAutoConfiguration.class, ServletWebSecurityAutoConfiguration.class
    }
)
@AutoConfigureMockMvc(addFilters = false)
@Import(FaceVerificationControllerTest.PrincipalConfig.class)
class PrimaryPhotoControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private NotificationEmitterRegistry notificationEmitterRegistry;

    @MockitoBean
    private NotificationSseObservation notificationSseObservation;

    @MockitoBean
    private PrimaryPhotoCheckService service;

    @Test
    void 기존_URL에서_대표사진_검증_통과_결과를_반환한다() throws Exception {
        given(service.check(41L, 7L)).willReturn(new PrimaryPhotoCheckResult(true, List.of()));
        mvc.perform(post("/api/v1/files/upload-intents/7/primary-photo-check")
            .requestAttr("principal", new ServiceAuthenticationPrincipal(41L)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("primary_photo_check_success"))
            .andExpect(jsonPath("$.data.passed").value(true))
            .andExpect(jsonPath("$.data.reasonCodes").isEmpty());
        verify(service).check(41L, 7L);
    }

    @Test
    void 대표사진_검증_실패는_200과_사유_코드로_반환한다() throws Exception {
        given(service.check(41L, 7L)).willReturn(new PrimaryPhotoCheckResult(
            false, List.of("NON_FRONTAL_FACE", "SYNTHETIC_RISK")));
        mvc.perform(post("/api/v1/files/upload-intents/7/primary-photo-check")
            .requestAttr("principal", new ServiceAuthenticationPrincipal(41L)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.passed").value(false))
            .andExpect(jsonPath("$.data.reasonCodes[0]").value("NON_FRONTAL_FACE"))
            .andExpect(jsonPath("$.data.reasonCodes[1]").value("SYNTHETIC_RISK"));
        verify(service).check(41L, 7L);
    }
}

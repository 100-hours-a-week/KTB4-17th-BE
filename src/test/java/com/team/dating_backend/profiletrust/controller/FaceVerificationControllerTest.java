package com.team.dating_backend.profiletrust.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.team.dating_backend.notification.sse.NotificationEmitterRegistry;
import com.team.dating_backend.profiletrust.dto.response.FaceVerificationResponse;
import com.team.dating_backend.profiletrust.entity.FaceVerificationAttempt;
import com.team.dating_backend.profiletrust.entity.FaceVerificationSession;
import com.team.dating_backend.profiletrust.enums.ProfileTrustErrorCode;
import com.team.dating_backend.profiletrust.enums.VerificationDecision;
import com.team.dating_backend.profiletrust.enums.VerificationReasonCode;
import com.team.dating_backend.profiletrust.exception.ProfileTrustBusinessException;
import com.team.dating_backend.profiletrust.service.FaceVerificationService;
import com.team.dating_backend.security.ServiceAuthenticationPrincipal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@WebMvcTest(
    controllers = FaceVerificationController.class, excludeAutoConfiguration = {
        SecurityAutoConfiguration.class, ServletWebSecurityAutoConfiguration.class
    }
)
@AutoConfigureMockMvc(addFilters = false)
@Import(FaceVerificationControllerTest.PrincipalConfig.class)
class FaceVerificationControllerTest {

    private static final String BASE = "/api/v1/users/me/face-verifications";
    private static final ServiceAuthenticationPrincipal PRINCIPAL = new ServiceAuthenticationPrincipal(41L);

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private NotificationEmitterRegistry notificationEmitterRegistry;

    @MockitoBean
    private FaceVerificationService service;

    @Test
    void 세션을_생성하고_촬영지시만_반환한다() throws Exception {
        given(service.create(41L, 7L)).willReturn(response());
        mvc.perform(post(BASE).requestAttr("principal", PRINCIPAL)
            .contentType(MediaType.APPLICATION_JSON).content("{\"uploadIntentId\":7}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.sessionId").value(9))
            .andExpect(jsonPath("$.data.maxAttempts").value(3))
            .andExpect(jsonPath("$.data.nextAttemptNo").value(1))
            .andExpect(jsonPath("$.data.attempts").isEmpty())
            .andExpect(jsonPath("$.data.challenges[0]").value("LOOK_STRAIGHT"))
            .andExpect(jsonPath("$.data.challengeToken").doesNotExist());
        verify(service).create(41L, 7L);
    }

    @Test
    void 영상과_시도번호를_서비스에_전달한다() throws Exception {
        given(service.submit(eq(41L), eq(9L), eq(1), any())).willReturn(response());
        MockMultipartFile video = new MockMultipartFile("video", "clip.webm", "video/webm", new byte[]{1});
        mvc.perform(multipart(BASE + "/9/attempts").file(video).param("attemptNo", "1")
            .requestAttr("principal", PRINCIPAL))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("face_verification_attempt_success"));
        verify(service).submit(41L, 9L, 1, video);
    }

    @Test
    void 첫_불일치에도_다음_시도번호와_사유를_반환한다() throws Exception {
        given(service.submit(eq(41L), eq(9L), eq(1), any()))
            .willReturn(response(List.of(VerificationDecision.NOT_VERIFIED)));
        mvc.perform(multipart(BASE + "/9/attempts")
            .file(new MockMultipartFile("video", "clip.webm", "video/webm", new byte[]{1}))
            .param("attemptNo", "1").requestAttr("principal", PRINCIPAL))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("READY"))
            .andExpect(jsonPath("$.data.canRetry").value(true))
            .andExpect(jsonPath("$.data.nextAttemptNo").value(2))
            .andExpect(jsonPath("$.data.attempt.decision").value("NOT_VERIFIED"))
            .andExpect(jsonPath("$.data.attempt.reasonCodes[0]").value("FACE_MISMATCH"));
    }

    @Test
    void 세번째_미통과_응답과_조회에_모든_시도결과를_포함한다() throws Exception {
        var exhausted = response(List.of(VerificationDecision.NOT_VERIFIED, VerificationDecision.RETRY,
            VerificationDecision.NOT_VERIFIED));
        given(service.submit(eq(41L), eq(9L), eq(3), any())).willReturn(exhausted);
        given(service.get(41L, 9L)).willReturn(exhausted);
        mvc.perform(multipart(BASE + "/9/attempts")
            .file(new MockMultipartFile("video", "clip.webm", "video/webm", new byte[]{3}))
            .param("attemptNo", "3").requestAttr("principal", PRINCIPAL))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("EXHAUSTED"))
            .andExpect(jsonPath("$.data.canRetry").value(false))
            .andExpect(jsonPath("$.data.attemptsUsed").value(3))
            .andExpect(jsonPath("$.data.remainingAttempts").value(0))
            .andExpect(jsonPath("$.data.nextAttemptNo").isEmpty())
            .andExpect(jsonPath("$.data.attempt.attemptNo").value(3))
            .andExpect(jsonPath("$.data.attempt.reasonCodes[0]").value("FACE_MISMATCH"))
            .andExpect(jsonPath("$.data.attempts.length()").value(3))
            .andExpect(jsonPath("$.data.attempts[0].attemptNo").value(1))
            .andExpect(jsonPath("$.data.attempts[1].reasonCodes[0]").value("LIVENESS_FAILED"))
            .andExpect(jsonPath("$.data.attempts[2].decision").value("NOT_VERIFIED"))
            .andExpect(jsonPath("$.data.attempts[0].videoHash").doesNotExist())
            .andExpect(jsonPath("$.data.challengeToken").doesNotExist());
        mvc.perform(get(BASE + "/9").requestAttr("principal", PRINCIPAL))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("EXHAUSTED"))
            .andExpect(jsonPath("$.data.attempts.length()").value(3))
            .andExpect(jsonPath("$.data.attempts[2].reasonCodes[0]").value("FACE_MISMATCH"));
    }

    @Test
    void 다른_사용자의_세션은_ErrorCode로_거부한다() throws Exception {
        given(service.get(41L, 9L)).willThrow(new ProfileTrustBusinessException(
            ProfileTrustErrorCode.VERIFICATION_SESSION_NOT_FOUND));
        mvc.perform(get(BASE + "/9").requestAttr("principal", PRINCIPAL))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.errorCode").value("VERIFICATION_SESSION_NOT_FOUND"));
    }

    @Test
    void 잘못된_intent와_영상_누락을_거부한다() throws Exception {
        mvc.perform(post(BASE).requestAttr("principal", PRINCIPAL)
            .contentType(MediaType.APPLICATION_JSON).content("{\"uploadIntentId\":0}"))
            .andExpect(status().isBadRequest());
        mvc.perform(multipart(BASE + "/9/attempts").param("attemptNo", "1")
            .requestAttr("principal", PRINCIPAL))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
    }

    private FaceVerificationResponse response() {
        return response(List.of());
    }

    private FaceVerificationResponse response(List<VerificationDecision> decisions) {
        FaceVerificationSession session = FaceVerificationSession.create(
            41L, 7L, "etag-1", "private-token", LocalDateTime.now(), LocalDateTime.now().plusMinutes(5));
        ReflectionTestUtils.setField(session, "id", 9L);
        List<FaceVerificationAttempt> attempts = new ArrayList<>();
        for (VerificationDecision decision : decisions) {
            int attemptNo = session.beginAttempt();
            var attempt = FaceVerificationAttempt.create(9L, attemptNo, "hash-" + attemptNo,
                "video/webm", LocalDateTime.now());
            List<VerificationReasonCode> reasons = switch (decision) {
                case VERIFIED -> List.of();
                case RETRY -> List.of(VerificationReasonCode.LIVENESS_FAILED);
                case NOT_VERIFIED -> List.of(VerificationReasonCode.FACE_MISMATCH);
            };
            attempt.complete(decision, reasons, LocalDateTime.now());
            session.complete(decision);
            attempts.add(attempt);
        }
        return FaceVerificationResponse.from(session, attempts.isEmpty() ? null : attempts.getLast(), attempts);
    }

    @TestConfiguration
    static class PrincipalConfig implements WebMvcConfigurer {

        @Override
        public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
            resolvers.add(new HandlerMethodArgumentResolver() {
                @Override
                public boolean supportsParameter(MethodParameter parameter) {
                    return parameter.hasParameterAnnotation(AuthenticationPrincipal.class)
                        && parameter.getParameterType() == ServiceAuthenticationPrincipal.class;
                }

                @Override
                public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
                    NativeWebRequest request, WebDataBinderFactory factory) {
                    return request.getAttribute("principal", NativeWebRequest.SCOPE_REQUEST);
                }
            });
        }
    }
}

package com.team.dating_backend.aisimulation.controller;

import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.CREATED_AT;
import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.SESSION_ID;
import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.TARGET_ID;
import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.USER_ID;
import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.publicDetail;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.team.dating_backend.aisimulation.dto.internal.AiSimulationExecutionDtos.AdmissionDecision;
import com.team.dating_backend.aisimulation.dto.request.AiSimulationCreateRequest;
import com.team.dating_backend.aisimulation.dto.response.AiSimulationResponses;
import com.team.dating_backend.aisimulation.enums.AiSimulationErrorCode;
import com.team.dating_backend.aisimulation.enums.AiSimulationSessionStatus;
import com.team.dating_backend.aisimulation.exception.AiSimulationBusinessException;
import com.team.dating_backend.aisimulation.exception.AiSimulationRequestPublishUnconfirmedException;
import com.team.dating_backend.aisimulation.service.AiSimulationQueryService;
import com.team.dating_backend.aisimulation.service.AiSimulationService;
import com.team.dating_backend.common.exception.GlobalExceptionHandler;
import com.team.dating_backend.security.ServiceAuthenticationPrincipal;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

class AiSimulationControllerTest {

    private AiSimulationService simulationService;
    private AiSimulationQueryService queryService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        simulationService = Mockito.mock(AiSimulationService.class);
        queryService = Mockito.mock(AiSimulationQueryService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
            new AiSimulationController(simulationService, queryService))
            .setControllerAdvice(new GlobalExceptionHandler(), new AiSimulationExceptionHandler())
            .setCustomArgumentResolvers(new PrincipalArgumentResolver())
            .build();
    }

    @Test
    void 접수하면_202와_세션_ID_상태를_반환한다() throws Exception {
        given(simulationService.request(USER_ID, new AiSimulationCreateRequest(TARGET_ID)))
            .willReturn(new AdmissionDecision(SESSION_ID, AiSimulationSessionStatus.IN_PROGRESS, true, CREATED_AT));

        mockMvc.perform(post("/api/v1/ai-simulations")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"targetUserId":8}
                """))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.message").value("ai_simulation_accepted"))
            .andExpect(jsonPath("$.data.simulationId").value(SESSION_ID))
            .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
            .andExpect(jsonPath("$.data.transcript").doesNotExist())
            .andExpect(jsonPath("$.data.created").doesNotExist())
            .andExpect(jsonPath("$.data.createdAt").doesNotExist());

        verify(simulationService).request(USER_ID, new AiSimulationCreateRequest(TARGET_ID));
    }

    @ParameterizedTest
    @EnumSource(AiSimulationSessionStatus.class)
    void 기존_세션은_상태와_무관하게_200으로_반환한다(AiSimulationSessionStatus sessionStatus) throws Exception {
        given(simulationService.request(USER_ID, new AiSimulationCreateRequest(TARGET_ID)))
            .willReturn(new AdmissionDecision(SESSION_ID, sessionStatus, false, CREATED_AT));
        mockMvc.perform(post("/api/v1/ai-simulations")
            .contentType(MediaType.APPLICATION_JSON).content("{\"targetUserId\":8}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("ai_simulation_existing"))
            .andExpect(jsonPath("$.data.status").value(sessionStatus.name()));
    }

    @Test
    void 상대_회원_ID가_없으면_400을_반환한다() throws Exception {
        mockMvc.perform(post("/api/v1/ai-simulations")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"))
            .andExpect(content().string("{\"errorCode\":\"INVALID_REQUEST\"}"));

        verify(simulationService, Mockito.never()).request(any(), any());
    }

    @ParameterizedTest
    @MethodSource("publicationFailures")
    void 발행_확인_실패는_503과_세션_ID를_반환하고_실행_실패_상태를_단정하지_않는다(Throwable cause) throws Exception {
        var failure = new AiSimulationRequestPublishUnconfirmedException(SESSION_ID, cause);
        given(simulationService.request(USER_ID, new AiSimulationCreateRequest(TARGET_ID))).willThrow(failure);

        mockMvc.perform(post("/api/v1/ai-simulations").contentType(MediaType.APPLICATION_JSON)
            .content("{\"targetUserId\":8}"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(
                content().string("{\"errorCode\":\"SIMULATION_REQUEST_PUBLISH_UNCONFIRMED\",\"simulationId\":31}"));

        assertThat(failure.getCause()).isSameAs(cause);
        assertThat(failure.getErrorCode()).isEqualTo(AiSimulationErrorCode.SIMULATION_REQUEST_PUBLISH_UNCONFIRMED);
        Mockito.verifyNoInteractions(queryService);
    }

    @Test
    void 일반_업무_오류의_기존_응답_형식을_유지한다() throws Exception {
        given(simulationService.request(USER_ID, new AiSimulationCreateRequest(TARGET_ID)))
            .willThrow(new AiSimulationBusinessException(AiSimulationErrorCode.SIMULATION_EVENT_PUBLISH_FAILED));

        mockMvc.perform(post("/api/v1/ai-simulations").contentType(MediaType.APPLICATION_JSON)
            .content("{\"targetUserId\":8}"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(content().string("{\"errorCode\":\"SIMULATION_EVENT_PUBLISH_FAILED\"}"));
    }

    @Test
    void 전용_오류_응답은_AI_시뮬레이션_Controller에만_적용한다() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new OtherController())
            .setControllerAdvice(new GlobalExceptionHandler(), new AiSimulationExceptionHandler()).build();

        mvc.perform(get("/other"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(content().string("{\"errorCode\":\"SIMULATION_REQUEST_PUBLISH_UNCONFIRMED\"}"));
    }

    private static Stream<Throwable> publicationFailures() {
        return Stream.of(new TimeoutException("private-broker-details"),
            new AiSimulationBusinessException(AiSimulationErrorCode.SIMULATION_EVENT_PUBLISH_FAILED,
                new ExecutionException(new org.apache.kafka.common.errors.TimeoutException("private-broker-details"))));
    }

    @TestComponent
    @RestController
    static class OtherController {

        @GetMapping("/other")
        public void request() {
            throw new AiSimulationRequestPublishUnconfirmedException(SESSION_ID, new TimeoutException());
        }
    }

    @Test
    void 목록과_상세와_리포트를_공통_응답으로_반환한다() throws Exception {
        AiSimulationResponses.Detail detail = publicDetail();
        AiSimulationResponses.Summary summary = new AiSimulationResponses.Summary(
            detail.simulationId(),
            detail.me(),
            detail.partner(),
            detail.turns(),
            detail.report().overall().score(),
            detail.report().overall().grade(),
            detail.report().overall().gradeLabel(),
            detail.report().overall().headline(),
            detail.createdAt());
        given(queryService.list(USER_ID)).willReturn(List.of(summary));
        given(queryService.get(USER_ID, SESSION_ID)).willReturn(detail);
        given(queryService.getReport(USER_ID, SESSION_ID)).willReturn(detail.report());

        mockMvc.perform(get("/api/v1/ai-simulations"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].simulationId").value(SESSION_ID));
        mockMvc.perform(get("/api/v1/ai-simulations/{id}", SESSION_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.partner.userId").value(TARGET_ID));
        mockMvc.perform(get("/api/v1/ai-simulations/{id}/report", SESSION_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.simulationId").value(SESSION_ID));
    }

    @Test
    void 소유하지_않은_시뮬레이션은_404를_반환한다() throws Exception {
        given(queryService.get(USER_ID, SESSION_ID)).willThrow(
            new AiSimulationBusinessException(AiSimulationErrorCode.SIMULATION_NOT_FOUND));

        mockMvc.perform(get("/api/v1/ai-simulations/{id}", SESSION_ID))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.errorCode").value("SIMULATION_NOT_FOUND"));
    }

    private static final class PrincipalArgumentResolver
        implements
            HandlerMethodArgumentResolver {

        @Override
        public boolean supportsParameter(MethodParameter parameter) {
            return parameter.getParameterType() == ServiceAuthenticationPrincipal.class;
        }

        @Override
        public Object resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory) {
            return new ServiceAuthenticationPrincipal(USER_ID);
        }
    }
}

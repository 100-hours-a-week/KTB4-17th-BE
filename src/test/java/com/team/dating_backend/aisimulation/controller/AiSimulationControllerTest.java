package com.team.dating_backend.aisimulation.controller;

import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.SESSION_ID;
import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.TARGET_ID;
import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.USER_ID;
import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.publicDetail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.team.dating_backend.aisimulation.dto.request.AiSimulationCreateRequest;
import com.team.dating_backend.aisimulation.dto.response.AiSimulationResponses;
import com.team.dating_backend.aisimulation.enums.AiSimulationErrorCode;
import com.team.dating_backend.aisimulation.enums.AiSimulationSessionStatus;
import com.team.dating_backend.aisimulation.exception.AiSimulationBusinessException;
import com.team.dating_backend.aisimulation.service.AiSimulationQueryService;
import com.team.dating_backend.aisimulation.service.AiSimulationService;
import com.team.dating_backend.common.exception.GlobalExceptionHandler;
import com.team.dating_backend.security.ServiceAuthenticationPrincipal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
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
            .setControllerAdvice(new GlobalExceptionHandler())
            .setCustomArgumentResolvers(new PrincipalArgumentResolver())
            .build();
    }

    @Test
    void 접수하면_202와_세션_ID_상태를_반환한다() throws Exception {
        given(simulationService.request(USER_ID, new AiSimulationCreateRequest(TARGET_ID)))
            .willReturn(new AiSimulationResponses.Handle(SESSION_ID, AiSimulationSessionStatus.IN_PROGRESS));

        mockMvc.perform(post("/api/v1/ai-simulations")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"targetMemberId":8}
                """))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.message").value("ai_simulation_accepted"))
            .andExpect(jsonPath("$.data.simulationId").value(SESSION_ID))
            .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
            .andExpect(jsonPath("$.data.transcript").doesNotExist());

        verify(simulationService).request(USER_ID, new AiSimulationCreateRequest(TARGET_ID));
    }

    @ParameterizedTest
    @EnumSource(value = AiSimulationSessionStatus.class, names = {"COMPLETED", "FAILED", "CANCELED"})
    void 종료된_기존_세션은_200으로_반환한다(AiSimulationSessionStatus sessionStatus) throws Exception {
        given(simulationService.request(USER_ID, new AiSimulationCreateRequest(TARGET_ID)))
            .willReturn(new AiSimulationResponses.Handle(SESSION_ID, sessionStatus));
        mockMvc.perform(post("/api/v1/ai-simulations")
            .contentType(MediaType.APPLICATION_JSON).content("{\"targetMemberId\":8}"))
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

package com.team.dating_backend.persona.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.team.dating_backend.common.exception.GlobalExceptionHandler;
import com.team.dating_backend.persona.dto.response.PersonaConversationResponse;
import com.team.dating_backend.persona.dto.response.PersonaDraftResponse;
import com.team.dating_backend.persona.dto.response.PersonaNarrativeResponse;
import com.team.dating_backend.persona.dto.response.PersonaSegmentResponse;
import com.team.dating_backend.persona.dto.response.PersonaSummaryResponse;
import com.team.dating_backend.persona.service.PersonaOnboardingService;
import com.team.dating_backend.security.ServiceAuthenticationPrincipal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PersonaControllerTest {

    private static final Long USER_ID = 7L;

    private PersonaOnboardingService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = Mockito.mock(PersonaOnboardingService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new PersonaController(service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setCustomArgumentResolvers(new PrincipalArgumentResolver())
            .build();
    }

    @Test
    void 문답을_시작하면_201과_첫_질문을_반환한다() throws Exception {
        given(service.start(USER_ID)).willReturn(conversation(false, false, 0));

        mockMvc.perform(post("/api/v1/persona/onboarding/start"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.message").value("persona_onboarding_started"))
            .andExpect(jsonPath("$.data.sessionId").value("session-1"))
            .andExpect(jsonPath("$.data.turnIndex").value(0))
            .andExpect(jsonPath("$.data.personaDraft").doesNotExist());

        verify(service).start(USER_ID);
    }

    @Test
    void 답변은_answer와_turnIndex를_서비스에_전달한다() throws Exception {
        given(service.answer(eq(USER_ID), eq("session-1"), any()))
            .willReturn(conversation(false, true, 0));

        mockMvc.perform(post("/api/v1/persona/onboarding/session-1/answer")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"answer":"  주말에는 산책해요  ","turnIndex":0}
                """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.retry").value(true))
            .andExpect(jsonPath("$.data.turnIndex").value(0));

        verify(service).answer(
            USER_ID,
            "session-1",
            new com.team.dating_backend.persona.dto.request.PersonaAnswerRequest(
                "주말에는 산책해요", 0));
    }

    @Test
    void turnIndex가_없으면_AI를_호출하지_않고_400을_반환한다() throws Exception {
        mockMvc.perform(post("/api/v1/persona/onboarding/session-1/answer")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"answer":"주말에는 산책해요"}
                """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"))
            .andExpect(content().string("{\"errorCode\":\"INVALID_REQUEST\"}"));
    }

    @Test
    void 조기종료는_전용_서비스를_호출한다() throws Exception {
        given(service.finish(USER_ID, "session-1"))
            .willReturn(conversation(true, false, 4));

        mockMvc.perform(post("/api/v1/persona/onboarding/session-1/finish"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("persona_onboarding_finished"))
            .andExpect(jsonPath("$.data.done").value(true));

        verify(service).finish(USER_ID, "session-1");
    }

    @Test
    void build는_narrative와_동적_결과_카드를_반환한다() throws Exception {
        given(service.build(USER_ID, "session-1")).willReturn(new PersonaDraftResponse(
            "persona-1",
            1,
            "llm",
            new PersonaNarrativeResponse(
                "천천히 깊어지는 사람",
                "각자의 시간을 존중하는 편이에요.",
                List.of("혼자만의 시간이 중요해요")),
            List.of(new PersonaSummaryResponse(
                "intimacy",
                "천천히 가까워지는 편",
                "서서히 알아가는 걸 편하게 느껴요"))));

        mockMvc.perform(post("/api/v1/persona/session-1/build"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.narrative.headline").value("천천히 깊어지는 사람"))
            .andExpect(jsonPath("$.data.narrative.traits[0]").value("혼자만의 시간이 중요해요"))
            .andExpect(jsonPath("$.data.summaries[0].category").value("intimacy"));
    }

    private PersonaConversationResponse conversation(
        boolean done,
        boolean retry,
        int turnIndex) {
        return new PersonaConversationResponse(
            "session-1",
            done ? "완료" : "질문",
            List.of(new PersonaSegmentResponse(done ? "closing" : "message", "발화")),
            done ? "4/4" : "1/10",
            done,
            done ? 4 : 0,
            !done,
            !done,
            retry,
            turnIndex,
            null);
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

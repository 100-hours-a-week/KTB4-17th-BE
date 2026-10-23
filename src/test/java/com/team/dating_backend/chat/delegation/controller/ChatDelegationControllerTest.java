package com.team.dating_backend.chat.delegation.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.team.dating_backend.chat.delegation.dto.response.ChatDelegationDraftResponse;
import com.team.dating_backend.chat.delegation.dto.response.ChatDelegationProgressResponse;
import com.team.dating_backend.chat.delegation.dto.response.ChatDelegationStartResponse;
import com.team.dating_backend.chat.delegation.enums.ChatDelegationEndType;
import com.team.dating_backend.chat.delegation.enums.ChatDelegationStatus;
import com.team.dating_backend.chat.enums.ChatErrorCode;
import com.team.dating_backend.chat.exception.ChatBusinessException;
import com.team.dating_backend.chat.delegation.service.ChatDelegationDraftService;
import com.team.dating_backend.chat.delegation.service.ChatDelegationProgressService;
import com.team.dating_backend.chat.delegation.service.ChatDelegationRetryService;
import com.team.dating_backend.chat.delegation.service.ChatDelegationStartService;
import com.team.dating_backend.common.exception.GlobalExceptionHandler;
import com.team.dating_backend.security.ServiceAuthenticationPrincipal;
import java.util.List;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

class ChatDelegationControllerTest {

    private ChatDelegationDraftService service;
    private ChatDelegationStartService startService;
    private ChatDelegationProgressService progressService;
    private ChatDelegationRetryService retryService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = Mockito.mock(ChatDelegationDraftService.class);
        startService = Mockito.mock(ChatDelegationStartService.class);
        progressService = Mockito.mock(ChatDelegationProgressService.class);
        retryService = Mockito.mock(ChatDelegationRetryService.class);
        mvc = MockMvcBuilders.standaloneSetup(
            new ChatDelegationController(
                service, startService, progressService, retryService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setCustomArgumentResolvers(new PrincipalResolver()).build();
    }

    @Test
    void 초안을_생성하면_201을_반환한다() throws Exception {
        when(service.create(10L, 1L, ChatDelegationEndType.GENTLE)).thenReturn(
            new ChatDelegationDraftResponse(20L, ChatDelegationStatus.AWAITING_CONFIRMATION,
                "a".repeat(32), List.of(new ChatDelegationDraftResponse.Turn(1, "첫 번째"))));
        mvc.perform(post("/api/v1/chat-rooms/10/delegations")
            .contentType(MediaType.APPLICATION_JSON).content("{\"endType\":\"GENTLE\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.message").value("chat_delegation_draft_created"))
            .andExpect(jsonPath("$.data.delegationId").value(20))
            .andExpect(jsonPath("$.data.status").value("AWAITING_CONFIRMATION"));
    }

    @Test
    void 위임_진행_상태를_조회하면_답장_내용을_노출하지_않는다() throws Exception {
        LocalDateTime sentAt = LocalDateTime.of(2026, 10, 22, 12, 0);
        when(progressService.getProgress(10L, 20L, 1L)).thenReturn(
            new ChatDelegationProgressResponse(
                20L, ChatDelegationStatus.IN_PROGRESS, false, 2L, List.of(
                    new ChatDelegationProgressResponse.TurnProgress(1, true, sentAt),
                    new ChatDelegationProgressResponse.TurnProgress(2, false, null),
                    new ChatDelegationProgressResponse.TurnProgress(3, false, null))));

        mvc.perform(get("/api/v1/chat-rooms/10/delegations/20"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("chat_delegation_progress_retrieved"))
            .andExpect(jsonPath("$.data.turns[0].completed").value(true))
            .andExpect(jsonPath("$.data.turns[0].sentAt").exists())
            .andExpect(jsonPath("$.data.turns[1].completed").value(false))
            .andExpect(jsonPath("$.data.turns[1].sentAt").doesNotExist())
            .andExpect(jsonPath("$.data.turns[0].message").doesNotExist());
    }

    @Test
    void 초안을_재생성하면_200을_반환한다() throws Exception {
        when(service.regenerate(10L, 20L, 1L, ChatDelegationEndType.DIRECT)).thenReturn(
            new ChatDelegationDraftResponse(20L, ChatDelegationStatus.AWAITING_CONFIRMATION,
                "b".repeat(32), List.of(
                    new ChatDelegationDraftResponse.Turn(1, "첫 번째"),
                    new ChatDelegationDraftResponse.Turn(2, "두 번째"),
                    new ChatDelegationDraftResponse.Turn(3, "세 번째"))));

        mvc.perform(post("/api/v1/chat-rooms/10/delegations/20/drafts")
            .contentType(MediaType.APPLICATION_JSON).content("{\"endType\":\"DIRECT\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("chat_delegation_draft_regenerated"))
            .andExpect(jsonPath("$.data.delegationId").value(20))
            .andExpect(jsonPath("$.data.draftId").value("b".repeat(32)))
            .andExpect(jsonPath("$.data.turns.length()").value(3));
    }

    @Test
    void 초안을_확정하면_위임_진행_상태를_반환한다() throws Exception {
        String draftId = "a".repeat(32);
        when(startService.start(10L, 20L, 1L, draftId)).thenReturn(
            new ChatDelegationStartResponse(20L, ChatDelegationStatus.IN_PROGRESS));

        mvc.perform(post("/api/v1/chat-rooms/10/delegations/20/confirm")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"draftId\":\"%s\"}".formatted(draftId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("chat_delegation_started"))
            .andExpect(jsonPath("$.data.delegationId").value(20))
            .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
            .andExpect(jsonPath("$.data.nextActionAt").doesNotExist());
    }

    @Test
    void 초안_ID_형식이_올바르지_않으면_입력_오류로_응답한다() throws Exception {
        mvc.perform(post("/api/v1/chat-rooms/10/delegations/20/confirm")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"draftId\":\"invalid\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(content().string("{\"errorCode\":\"INVALID_REQUEST\"}"));
        verifyNoInteractions(startService);
    }

    @Test
    void 지원하지_않는_종료_유형이면_입력_오류로_응답한다() throws Exception {
        mvc.perform(post("/api/v1/chat-rooms/10/delegations")
            .contentType(MediaType.APPLICATION_JSON).content("{\"endType\":\"UNKNOWN\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(content().string("{\"errorCode\":\"INVALID_REQUEST\"}"));
        verifyNoInteractions(service);
    }

    @Test
    void 이미_위임이_존재하면_중복_위임_오류로_응답한다() throws Exception {
        when(service.create(10L, 1L, ChatDelegationEndType.CASUAL)).thenThrow(
            new ChatBusinessException(ChatErrorCode.CHAT_DELEGATION_ALREADY_EXISTS));
        mvc.perform(post("/api/v1/chat-rooms/10/delegations")
            .contentType(MediaType.APPLICATION_JSON).content("{\"endType\":\"CASUAL\"}"))
            .andExpect(status().isConflict())
            .andExpect(content().string("{\"errorCode\":\"CHAT_DELEGATION_ALREADY_EXISTS\"}"));
    }

    private static final class PrincipalResolver implements HandlerMethodArgumentResolver {
        public boolean supportsParameter(MethodParameter parameter) {
            return parameter.getParameterType() == ServiceAuthenticationPrincipal.class;
        }

        public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
            NativeWebRequest request, WebDataBinderFactory factory) {
            return new ServiceAuthenticationPrincipal(1L);
        }
    }
}

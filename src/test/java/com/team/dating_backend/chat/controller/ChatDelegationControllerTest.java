package com.team.dating_backend.chat.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.team.dating_backend.chat.dto.response.ChatDelegationDraftResponse;
import com.team.dating_backend.chat.enums.ChatDelegationEndType;
import com.team.dating_backend.chat.enums.ChatDelegationStatus;
import com.team.dating_backend.chat.enums.ChatErrorCode;
import com.team.dating_backend.chat.exception.ChatBusinessException;
import com.team.dating_backend.chat.service.ChatDelegationDraftService;
import com.team.dating_backend.common.exception.GlobalExceptionHandler;
import com.team.dating_backend.security.ServiceAuthenticationPrincipal;
import java.util.List;
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
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = Mockito.mock(ChatDelegationDraftService.class);
        mvc = MockMvcBuilders.standaloneSetup(new ChatDelegationController(service))
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
    void 잘못된_종료_유형은_errorCode만_반환한다() throws Exception {
        mvc.perform(post("/api/v1/chat-rooms/10/delegations")
            .contentType(MediaType.APPLICATION_JSON).content("{\"endType\":\"UNKNOWN\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(content().string("{\"errorCode\":\"INVALID_REQUEST\"}"));
        verifyNoInteractions(service);
    }

    @Test
    void 비즈니스_오류는_정의된_errorCode만_반환한다() throws Exception {
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

package com.team.dating_backend.chat.delegation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.team.dating_backend.chat.delegation.client.ChatDelegationAiClient;
import com.team.dating_backend.chat.delegation.dto.response.ChatDelegationStartResponse;
import com.team.dating_backend.chat.delegation.enums.ChatDelegationStatus;
import com.team.dating_backend.chat.enums.ChatErrorCode;
import com.team.dating_backend.chat.exception.ChatBusinessException;
import com.team.dating_backend.chat.delegation.service.ChatDelegationStartPersistenceService.Confirmation;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChatDelegationStartServiceTest {

    private static final String DRAFT_ID = "a".repeat(32);
    private ChatDelegationStartPersistenceService persistenceService;
    private ChatDelegationAiClient aiClient;
    private ChatDelegationStartService service;

    @BeforeEach
    void setUp() {
        persistenceService = mock(ChatDelegationStartPersistenceService.class);
        aiClient = mock(ChatDelegationAiClient.class);
        service = new ChatDelegationStartService(persistenceService, aiClient);
    }

    @Test
    void AI_초안을_확정하고_위임을_시작한다() {
        Confirmation confirmation = new Confirmation(20L, DRAFT_ID, false);
        ChatDelegationStartResponse expected = new ChatDelegationStartResponse(
            20L, ChatDelegationStatus.IN_PROGRESS);
        when(persistenceService.prepare(10L, 20L, 1L, DRAFT_ID))
            .thenReturn(confirmation);
        when(persistenceService.start(
            eq(10L), eq(1L), eq(confirmation), any(LocalDateTime.class)))
            .thenReturn(expected);

        assertThat(service.start(10L, 20L, 1L, DRAFT_ID)).isEqualTo(expected);
        verify(aiClient).confirmDraft(DRAFT_ID, 20L);
    }

    @Test
    void AI_서버에_선택한_초안을_반영하지_못하면_위임을_시작하지_않는다() {
        Confirmation confirmation = new Confirmation(20L, DRAFT_ID, false);
        ChatBusinessException failure = new ChatBusinessException(
            ChatErrorCode.CHAT_DELEGATION_AI_UNAVAILABLE);
        when(persistenceService.prepare(10L, 20L, 1L, DRAFT_ID))
            .thenReturn(confirmation);
        when(aiClient.confirmDraft(DRAFT_ID, 20L)).thenThrow(failure);

        assertThatThrownBy(() -> service.start(10L, 20L, 1L, DRAFT_ID)).isSameAs(failure);
        verify(persistenceService, never()).start(
            eq(10L), eq(1L), eq(confirmation), any(LocalDateTime.class));
    }

    @Test
    void 이미_시작한_위임은_AI를_다시_호출하지_않는다() {
        when(persistenceService.prepare(10L, 20L, 1L, DRAFT_ID))
            .thenReturn(new Confirmation(20L, DRAFT_ID, true));

        ChatDelegationStartResponse response = service.start(10L, 20L, 1L, DRAFT_ID);

        assertThat(response.status()).isEqualTo(ChatDelegationStatus.IN_PROGRESS);
        verify(aiClient, never()).confirmDraft(DRAFT_ID, 20L);
    }
}

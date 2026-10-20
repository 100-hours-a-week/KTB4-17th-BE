package com.team.dating_backend.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.team.dating_backend.chat.client.ChatDelegationAiClient;
import com.team.dating_backend.chat.dto.ai.ChatDelegationAiPayloads.DraftRequest;
import com.team.dating_backend.chat.dto.ai.ChatDelegationAiPayloads.DraftResponse;
import com.team.dating_backend.chat.dto.ai.ChatDelegationAiPayloads.Turn;
import com.team.dating_backend.chat.enums.ChatDelegationEndType;
import com.team.dating_backend.chat.enums.ChatDelegationStatus;
import com.team.dating_backend.chat.enums.ChatErrorCode;
import com.team.dating_backend.chat.exception.ChatBusinessException;
import com.team.dating_backend.chat.service.ChatDelegationDraftPersistenceService.Preparation;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChatDelegationDraftServiceTest {

    private static final UUID REQUEST_ID = UUID.fromString(
        "11111111-1111-1111-1111-111111111111");
    private ChatDelegationDraftPersistenceService persistence;
    private ChatDelegationAiClient aiClient;
    private ChatDelegationDraftService service;

    @BeforeEach
    void setUp() {
        persistence = mock(ChatDelegationDraftPersistenceService.class);
        aiClient = mock(ChatDelegationAiClient.class);
        service = new ChatDelegationDraftService(persistence, aiClient);
    }

    @Test
    void AI_초안을_저장하고_사용자에게_반환한다() {
        DraftRequest request = mock(DraftRequest.class);
        Preparation preparation = new Preparation(3L, REQUEST_ID, request);
        when(persistence.prepare(1L, 2L, ChatDelegationEndType.GENTLE))
            .thenReturn(preparation);
        when(aiClient.createDraft(request)).thenReturn(response());

        var response = service.create(1L, 2L, ChatDelegationEndType.GENTLE);

        assertThat(response.delegationId()).isEqualTo(3L);
        assertThat(response.turns()).extracting("turn").containsExactly(1, 2, 3);
        verify(persistence).complete(preparation, "a".repeat(32));
    }

    @Test
    void 최초_초안_생성에_실패하면_실패_상태를_저장한다() {
        DraftRequest request = mock(DraftRequest.class);
        Preparation preparation = new Preparation(3L, REQUEST_ID, request);
        ChatBusinessException failure = new ChatBusinessException(
            ChatErrorCode.CHAT_DELEGATION_AI_UNAVAILABLE);
        when(persistence.prepare(1L, 2L, ChatDelegationEndType.GENTLE))
            .thenReturn(preparation);
        when(aiClient.createDraft(request)).thenThrow(failure);

        assertThatThrownBy(() -> service.create(1L, 2L, ChatDelegationEndType.GENTLE))
            .isSameAs(failure);
        verify(persistence).fail(preparation);
    }

    @Test
    void 기존_위임의_초안을_재생성한다() {
        DraftRequest request = mock(DraftRequest.class);
        Preparation preparation = new Preparation(3L, REQUEST_ID, request);
        when(persistence.prepareRegeneration(
            1L, 3L, 2L, ChatDelegationEndType.DIRECT)).thenReturn(preparation);
        when(aiClient.createDraft(request)).thenReturn(response());

        var response = service.regenerate(1L, 3L, 2L, ChatDelegationEndType.DIRECT);

        assertThat(response.status()).isEqualTo(ChatDelegationStatus.AWAITING_CONFIRMATION);
        verify(persistence).complete(preparation, "a".repeat(32));
    }

    @Test
    void 재생성에_실패하면_실패_상태를_저장한다() {
        DraftRequest request = mock(DraftRequest.class);
        Preparation preparation = new Preparation(3L, REQUEST_ID, request);
        ChatBusinessException failure = new ChatBusinessException(
            ChatErrorCode.CHAT_DELEGATION_AI_RESPONSE_INVALID);
        when(persistence.prepareRegeneration(
            1L, 3L, 2L, ChatDelegationEndType.DIRECT)).thenReturn(preparation);
        when(aiClient.createDraft(request)).thenThrow(failure);

        assertThatThrownBy(() -> service.regenerate(
            1L, 3L, 2L, ChatDelegationEndType.DIRECT)).isSameAs(failure);
        verify(persistence).fail(preparation);
    }

    private DraftResponse response() {
        return new DraftResponse(1L, "a".repeat(32), "PENDING", List.of(
            new Turn(1, "하나"), new Turn(2, "둘"), new Turn(3, "셋")));
    }

}

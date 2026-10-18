package com.team.dating_backend.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.team.dating_backend.chat.client.ChatDelegationAiClient;
import com.team.dating_backend.chat.dto.ai.ChatDelegationAiPayloads.DraftRequest;
import com.team.dating_backend.chat.dto.ai.ChatDelegationAiPayloads.DraftResponse;
import com.team.dating_backend.chat.dto.ai.ChatDelegationAiPayloads.Turn;
import com.team.dating_backend.chat.enums.ChatDelegationEndType;
import com.team.dating_backend.chat.service.ChatDelegationDraftPersistenceService.Preparation;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChatDelegationDraftServiceTest {

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
        when(persistence.prepare(1L, 2L, ChatDelegationEndType.GENTLE))
            .thenReturn(new Preparation(3L, request));
        when(aiClient.createDraft(request)).thenReturn(new DraftResponse(
            1L, "a".repeat(32), "PENDING", List.of(
                new Turn(1, "하나"), new Turn(2, "둘"), new Turn(3, "셋"))));

        var response = service.create(1L, 2L, ChatDelegationEndType.GENTLE);

        assertThat(response.delegationId()).isEqualTo(3L);
        assertThat(response.turns()).extracting("turn").containsExactly(1, 2, 3);
        verify(persistence).complete(3L, "a".repeat(32));
    }
}

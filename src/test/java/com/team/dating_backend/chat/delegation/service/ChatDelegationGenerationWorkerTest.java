package com.team.dating_backend.chat.delegation.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.team.dating_backend.chat.delegation.client.ChatDelegationAiClient;
import com.team.dating_backend.chat.delegation.dto.ai.ChatDelegationAiPayloads.MessageRequest;
import com.team.dating_backend.chat.delegation.dto.ai.ChatDelegationAiPayloads.MessageResponse;
import com.team.dating_backend.chat.delegation.service.ChatDelegationGenerationPersistenceService.GenerationInput;
import com.team.dating_backend.chat.enums.ChatErrorCode;
import com.team.dating_backend.chat.exception.ChatBusinessException;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChatDelegationGenerationWorkerTest {

    private ChatDelegationGenerationPersistenceService persistenceService;
    private ChatDelegationTerminationService terminationService;
    private ChatDelegationDeliveryService deliveryService;
    private ChatDelegationAiClient aiClient;
    private ChatDelegationGenerationWorker worker;
    private GenerationInput input;

    @BeforeEach
    void setUp() {
        persistenceService = mock(ChatDelegationGenerationPersistenceService.class);
        terminationService = mock(ChatDelegationTerminationService.class);
        deliveryService = mock(ChatDelegationDeliveryService.class);
        aiClient = mock(ChatDelegationAiClient.class);
        worker = new ChatDelegationGenerationWorker(
            persistenceService, terminationService, deliveryService, aiClient);
        UUID requestId = UUID.randomUUID();
        input = new GenerationInput(
            30L, requestId, 1, mock(MessageRequest.class));
        when(persistenceService.prepareNew(any(), any()))
            .thenReturn(Optional.of(input));
    }

    @Test
    void AI가_답장을_생성하면_완료_결과를_반영한다() {
        MessageResponse response = new MessageResponse(
            10L, 50L, "답장", "SUCCESS", 1, false, 1);
        when(aiClient.createMessage(input.request(), 1)).thenReturn(response);

        worker.generate(20L);

        verify(persistenceService).complete(input, response);
        verify(deliveryService).deliver(eq(30L), any(LocalDateTime.class));
    }

    @Test
    void AI가_FAILED를_반환하면_생성_재시도를_예약한다() {
        MessageResponse response = new MessageResponse(
            10L, 50L, "오류", "FAILED", 1, false, 0);
        when(aiClient.createMessage(input.request(), 1)).thenReturn(response);

        worker.generate(20L);

        verify(persistenceService).retryGeneration(any(), any(LocalDateTime.class));
    }

    @Test
    void AI_답장_전송_작업을_실행한다() {
        worker.deliver(30L);

        verify(deliveryService).deliver(eq(30L), any(LocalDateTime.class));
    }

    @Test
    void AI_서버에_연결하지_못하면_자동_재시도를_예약한다() {
        when(aiClient.createMessage(input.request(), 1))
            .thenThrow(new ChatBusinessException(
                ChatErrorCode.CHAT_DELEGATION_AI_UNAVAILABLE));

        worker.generate(20L);

        verify(persistenceService).retryTransport(any(), any(LocalDateTime.class));
    }

    @Test
    void AI_응답_검증에_실패하면_사용자_재시도_상태로_전환한다() {
        when(aiClient.createMessage(input.request(), 1))
            .thenThrow(new ChatBusinessException(
                ChatErrorCode.CHAT_DELEGATION_AI_RESPONSE_INVALID));

        worker.generate(20L);

        verify(persistenceService).requireRetry(input);
    }
}

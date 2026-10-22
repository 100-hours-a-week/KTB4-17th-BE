package com.team.dating_backend.chat.delegation.service;

import com.team.dating_backend.chat.delegation.client.ChatDelegationAiClient;
import com.team.dating_backend.chat.delegation.dto.ai.ChatDelegationAiPayloads.MessageResponse;
import com.team.dating_backend.chat.delegation.service.ChatDelegationGenerationPersistenceService.GenerationInput;
import com.team.dating_backend.chat.enums.ChatErrorCode;
import com.team.dating_backend.chat.exception.ChatBusinessException;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(
    prefix = "app.chat", name = "runtime-enabled", havingValue = "false",
    matchIfMissing = true
)
public class ChatDelegationGenerationWorker {

    private static final int RECOVERY_MINUTES = 2;

    private final ChatDelegationGenerationPersistenceService persistenceService;
    private final ChatDelegationAiClient aiClient;

    @Async("chatDelegationExecutor")
    public void generate(Long delegationId) {
        LocalDateTime now = LocalDateTime.now();
        execute(persistenceService.prepareNew(delegationId, now));
    }

    @Async("chatDelegationExecutor")
    public void retry(Long messageId) {
        LocalDateTime now = LocalDateTime.now();
        execute(persistenceService.prepareRetry(messageId, now));
    }

    @Async("chatDelegationExecutor")
    public void recover(Long messageId) {
        LocalDateTime now = LocalDateTime.now();
        execute(persistenceService.prepareRecovery(
            messageId, now.minusMinutes(RECOVERY_MINUTES), now));
    }

    private void execute(Optional<GenerationInput> candidate) {
        if (candidate.isEmpty()) {
            return;
        }
        GenerationInput input = candidate.get();
        try {
            MessageResponse response = aiClient.createMessage(input.request(), input.turn());
            if ("FAILED".equals(response.status())) {
                persistenceService.retryGeneration(input, LocalDateTime.now());
            } else {
                persistenceService.complete(input, response);
            }
        } catch (ChatBusinessException exception) {
            handleAiFailure(input, exception);
        } catch (RuntimeException exception) {
            log.error(
                "Chat delegation generation failed. delegationMessageId={}, exceptionType={}",
                input.messageId(), exception.getClass().getSimpleName());
        }
    }

    private void handleAiFailure(GenerationInput input, ChatBusinessException exception) {
        try {
            if (exception.getErrorCode() == ChatErrorCode.CHAT_DELEGATION_AI_UNAVAILABLE) {
                persistenceService.retryTransport(input, LocalDateTime.now());
            } else {
                persistenceService.requireRetry(input);
            }
        } catch (RuntimeException persistenceException) {
            log.error(
                "Chat delegation failure persistence failed. delegationMessageId={}, exceptionType={}",
                input.messageId(), persistenceException.getClass().getSimpleName());
        }
    }
}

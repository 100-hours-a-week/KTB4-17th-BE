package com.team.dating_backend.aipractice.service;

import com.team.dating_backend.aipractice.client.AiPracticeAiClient;
import com.team.dating_backend.aipractice.config.AiPracticeProperties;
import com.team.dating_backend.aipractice.dto.ai.AiPracticeAiPayloads.GenerationReplyResponse;
import com.team.dating_backend.aipractice.entity.AiPracticeChat;
import com.team.dating_backend.aipractice.entity.AiPracticeOutbox;
import com.team.dating_backend.aipractice.entity.AiPracticeSession;
import com.team.dating_backend.aipractice.enums.AiPracticeChatStatus;
import com.team.dating_backend.aipractice.enums.AiPracticeErrorCode;
import com.team.dating_backend.aipractice.enums.AiPracticeOutboxCommandType;
import com.team.dating_backend.aipractice.exception.AiPracticeBusinessException;
import com.team.dating_backend.aipractice.repository.AiPracticeChatRepository;
import com.team.dating_backend.aipractice.repository.AiPracticeOutboxRepository;
import com.team.dating_backend.aipractice.repository.AiPracticeSessionRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Slf4j
@Component
@RequiredArgsConstructor
public class AiPracticeOutboxPublishJob {

    private static final int BATCH_SIZE = 50;
    private static final int MAX_BACKOFF_EXPONENT = 8;

    private final AiPracticeOutboxRepository outboxRepository;
    private final AiPracticeSessionRepository sessionRepository;
    private final AiPracticeChatRepository chatRepository;
    private final AiPracticeAiClient aiClient;
    private final AiPracticeOutboxResultService resultService;
    private final AiPracticeProperties properties;

    @Scheduled(fixedDelayString = "${app.ai-practice.outbox-publish-delay-ms:1000}")
    public void publishDueCommands() {
        List<AiPracticeOutbox> outboxes = outboxRepository
            .findByPublishedAtIsNullAndFailedAtIsNullAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscIdAsc(
                AiPracticeTime.now(), PageRequest.of(0, BATCH_SIZE));
        for (AiPracticeOutbox outbox : outboxes) {
            try {
                publish(outbox);
            } catch (RuntimeException exception) {
                recordFailure(outbox, exception);
            }
        }
    }

    private void publish(AiPracticeOutbox outbox) {
        AiPracticeSession session = sessionRepository.findById(outbox.getSessionId()).orElse(null);
        if (session == null) {
            resultService.markSkipped(outbox.getId());
            return;
        }

        if (outbox.getCommandType() == AiPracticeOutboxCommandType.END_SESSION) {
            if (session.getAiSessionId() == null) {
                resultService.markSkipped(outbox.getId());
                return;
            }
            aiClient.endSession(session.getAiSessionId());
            resultService.markEndSubmitted(outbox.getId());
            return;
        }

        AiPracticeChat chat = outbox.getChatId() == null
            ? null
            : chatRepository.findByIdAndSession_Id(outbox.getChatId(), outbox.getSessionId())
                .orElse(null);
        if (chat == null
            || chat.getStatus() != AiPracticeChatStatus.GENERATING
            || chat.getGenerationAttempt() != outbox.getGenerationAttempt()) {
            resultService.markSkipped(outbox.getId());
            return;
        }

        boolean retryExistingAiMessage = chat.isRetry() && session.getAiSessionId() != null;
        try {
            String aiSessionId = session.getAiSessionId();
            if (aiSessionId == null) {
                if (session.getTargetMemberId() == null) {
                    throw new AiPracticeBusinessException(
                        AiPracticeErrorCode.TARGET_MEMBER_UNAVAILABLE);
                }
                aiSessionId = aiClient.startSession(
                    session.getUserId(), session.getTargetMemberId());
                resultService.attachAiSessionId(outbox.getId(), aiSessionId);
            }

            GenerationReplyResponse response;
            if (retryExistingAiMessage) {
                response = aiClient.retryMessage(aiSessionId);
            } else {
                response = aiClient.sendMessage(aiSessionId, chat.getUserMessage());
            }
            resultService.markGenerationCompleted(outbox.getId(), aiSessionId, response.content());
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            if (status == 503 || status >= 400 && status < 500) {
                resultService.markGenerationFailed(outbox.getId(), getPermanentFailureCode(status));
                return;
            }
            throw exception;
        } catch (AiPracticeBusinessException exception) {
            String failureCode = getImmediateFailureCode(exception);
            if (failureCode == null) {
                throw exception;
            }
            resultService.markGenerationFailed(outbox.getId(), failureCode);
        }
    }

    private void recordFailure(AiPracticeOutbox outbox, RuntimeException exception) {
        LocalDateTime now = AiPracticeTime.now();
        long maxDelaySeconds = 1L << Math.min(outbox.getFailureCount(), MAX_BACKOFF_EXPONENT);
        long minimumDelaySeconds = Math.max(1, maxDelaySeconds / 2);
        long delayRange = maxDelaySeconds - minimumDelaySeconds + 1;
        long retryDelaySeconds = minimumDelaySeconds
            + ThreadLocalRandom.current().nextLong(delayRange);
        String failureType = getFailureType(exception);

        resultService.recordFailure(
            outbox.getId(),
            failureType,
            properties.getMaxOutboxFailures(),
            now.plusSeconds(retryDelaySeconds));
        log.warn("AI Practice outbox delivery failed. outboxId={}, failureType={}, exceptionType={}",
            outbox.getId(), failureType, exception.getClass().getSimpleName());
    }

    private String getFailureType(RuntimeException exception) {
        if (exception instanceof AiPracticeBusinessException businessException) {
            return businessException.getErrorCode().toString();
        }
        if (exception instanceof RestClientException) {
            return "AI_SERVER_REQUEST_FAILED";
        }
        return "AI_PRACTICE_DISPATCH_FAILED";
    }

    private String getPermanentFailureCode(int status) {
        return switch (status) {
            case 404 -> "AI_PERSONA_OR_SESSION_NOT_FOUND";
            case 409 -> "AI_SESSION_CONFLICT";
            case 422 -> "AI_REQUEST_REJECTED";
            case 503 -> "AI_GENERATION_FAILED";
            default -> "AI_SERVER_HTTP_" + status;
        };
    }

    private String getImmediateFailureCode(AiPracticeBusinessException exception) {
        return switch (exception.getErrorCode().name()) {
            case "AI_SERVER_NOT_CONFIGURED",
                "AI_SERVER_RESPONSE_INVALID",
                "AI_SESSION_ID_CONFLICT",
                "TARGET_MEMBER_UNAVAILABLE" -> exception.getErrorCode().name();
            default -> null;
        };
    }
}

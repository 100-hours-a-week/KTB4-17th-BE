package com.team.dating_backend.aipractice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.team.dating_backend.aipractice.client.AiPracticeAiClient;
import com.team.dating_backend.aipractice.config.AiPracticeProperties;
import com.team.dating_backend.aipractice.dto.ai.AiPracticeAiPayloads.GenerationReplyResponse;
import com.team.dating_backend.aipractice.entity.AiPracticeChat;
import com.team.dating_backend.aipractice.entity.AiPracticeOutbox;
import com.team.dating_backend.aipractice.entity.AiPracticeSession;
import com.team.dating_backend.aipractice.enums.AiPracticeOutboxCommandType;
import com.team.dating_backend.aipractice.repository.AiPracticeChatRepository;
import com.team.dating_backend.aipractice.repository.AiPracticeOutboxRepository;
import com.team.dating_backend.aipractice.repository.AiPracticeSessionRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClientException;

class AiPracticeOutboxPublishJobTest {

    private static final Long USER_ID = 1L;
    private static final Long SESSION_ID = 10L;
    private static final Long CHAT_ID = 20L;
    private static final Long OUTBOX_ID = 30L;
    private static final Long TARGET_USER_ID = 2L;
    private static final String PROCESSING_TOKEN = "9d525e70-28d0-4214-b3ac-dc45f9d073ca";
    private static final UUID IDEMPOTENCY_KEY = UUID.fromString(
        "123e4567-e89b-12d3-a456-426614174000");
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 9, 27, 12, 0);

    private AiPracticeOutboxRepository outboxRepository;
    private AiPracticeSessionRepository sessionRepository;
    private AiPracticeChatRepository chatRepository;
    private AiPracticeAiClient aiClient;
    private AiPracticeOutboxClaimService claimService;
    private AiPracticeOutboxResultService resultService;
    private AiPracticeProperties properties;
    private AiPracticeOutboxPublishJob job;
    private AiPracticeOutbox outbox;
    private AiPracticeSession session;
    private AiPracticeChat chat;

    @BeforeEach
    void setUp() {
        outboxRepository = mock(AiPracticeOutboxRepository.class);
        sessionRepository = mock(AiPracticeSessionRepository.class);
        chatRepository = mock(AiPracticeChatRepository.class);
        aiClient = mock(AiPracticeAiClient.class);
        claimService = mock(AiPracticeOutboxClaimService.class);
        resultService = mock(AiPracticeOutboxResultService.class);
        properties = new AiPracticeProperties();
        job = new AiPracticeOutboxPublishJob(
            outboxRepository,
            sessionRepository,
            chatRepository,
            aiClient,
            claimService,
            resultService,
            properties);

        session = new AiPracticeSession(USER_ID, TARGET_USER_ID, CREATED_AT);
        ReflectionTestUtils.setField(session, "id", SESSION_ID);
        chat = new AiPracticeChat(
            session, java.util.UUID.randomUUID(), "연습 메시지", AiPracticeTime.today(), CREATED_AT);
        ReflectionTestUtils.setField(chat, "id", CHAT_ID);
        outbox = new AiPracticeOutbox(
            AiPracticeOutboxCommandType.GENERATE, SESSION_ID, CHAT_ID, 1, CREATED_AT);
        ReflectionTestUtils.setField(outbox, "id", OUTBOX_ID);
        ReflectionTestUtils.setField(outbox, "idempotencyKey", IDEMPOTENCY_KEY);
        ReflectionTestUtils.setField(outbox, "processingToken", PROCESSING_TOKEN);

        given(outboxRepository.findAvailable(
            any(LocalDateTime.class), any(Pageable.class)))
            .willReturn(List.of(outbox));
        given(claimService.tryClaim(OUTBOX_ID)).willReturn(Optional.of(outbox));
        given(claimService.renew(OUTBOX_ID, PROCESSING_TOKEN)).willReturn(true);
        given(resultService.attachAiSessionId(
            OUTBOX_ID, PROCESSING_TOKEN, "ai-session-42")).willReturn(true);
        given(sessionRepository.findById(SESSION_ID)).willReturn(Optional.of(session));
        given(chatRepository.findByIdAndSessionId(CHAT_ID, SESSION_ID))
            .willReturn(Optional.of(chat));
    }

    @Test
    void 새_AI_세션에서_사용자_메시지에_대한_AI_답변을_저장한다() {
        given(aiClient.startSession(USER_ID, TARGET_USER_ID, IDEMPOTENCY_KEY))
            .willReturn("ai-session-42");
        given(aiClient.sendMessage(anyString(), anyString(), any(UUID.class)))
            .willReturn(new GenerationReplyResponse("ai-session-42", 1, "AI 답변", "llm"));

        job.publishDueCommands();

        verify(aiClient).startSession(USER_ID, TARGET_USER_ID, IDEMPOTENCY_KEY);
        verify(aiClient).sendMessage("ai-session-42", "연습 메시지", IDEMPOTENCY_KEY);
        verify(resultService).attachAiSessionId(
            OUTBOX_ID, PROCESSING_TOKEN, "ai-session-42");
        verify(resultService).markGenerationCompleted(
            OUTBOX_ID, PROCESSING_TOKEN, "ai-session-42", "AI 답변");
    }

    @Test
    void 기존_AI_세션에_사용자_메시지를_전달하고_AI_답변을_저장한다() {
        session.attachAiSessionId("ai-session-42");
        given(aiClient.sendMessage(anyString(), anyString(), any(UUID.class)))
            .willReturn(new GenerationReplyResponse("ai-session-42", 1, "AI 답변", "llm"));

        job.publishDueCommands();

        verify(aiClient).sendMessage("ai-session-42", "연습 메시지", IDEMPOTENCY_KEY);
        verify(resultService).markGenerationCompleted(
            OUTBOX_ID, PROCESSING_TOKEN, "ai-session-42", "AI 답변");
        verify(aiClient, never()).startSession(any(), any(), any());
    }

    @Test
    void 현재_생성_요청과_일치하지_않으면_AI_서버를_호출하지_않는다() {
        ReflectionTestUtils.setField(outbox, "generationAttempt", 0);

        job.publishDueCommands();

        verify(resultService).markSkipped(OUTBOX_ID, PROCESSING_TOKEN);
        verifyNoInteractions(aiClient);
    }

    @Test
    void AI_서버_요청에_실패하면_재시도를_예약한다() {
        org.mockito.BDDMockito.willThrow(new RestClientException("AI_SERVER_REQUEST_FAILED"))
            .given(aiClient).startSession(USER_ID, TARGET_USER_ID, IDEMPOTENCY_KEY);

        job.publishDueCommands();

        org.mockito.ArgumentCaptor<String> failureTypeCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<Integer> maxFailuresCaptor = org.mockito.ArgumentCaptor.forClass(Integer.class);
        org.mockito.ArgumentCaptor<LocalDateTime> retryAtCaptor = org.mockito.ArgumentCaptor
            .forClass(LocalDateTime.class);
        verify(resultService).recordFailure(
            org.mockito.ArgumentMatchers.eq(OUTBOX_ID),
            org.mockito.ArgumentMatchers.eq(PROCESSING_TOKEN),
            failureTypeCaptor.capture(),
            maxFailuresCaptor.capture(),
            retryAtCaptor.capture());
        assertThat(failureTypeCaptor.getValue()).isEqualTo("AI_SERVER_REQUEST_FAILED");
        assertThat(maxFailuresCaptor.getValue()).isEqualTo(10);
        assertThat(retryAtCaptor.getValue()).isAfter(AiPracticeTime.now());
        verify(resultService, never()).markGenerationCompleted(
            OUTBOX_ID, PROCESSING_TOKEN, "ai-session-42", "AI 답변");
    }

    @Test
    void 다른_서버가_처리_중인_요청은_건너뛴다() {
        given(claimService.tryClaim(OUTBOX_ID)).willReturn(Optional.empty());

        job.publishDueCommands();

        verifyNoInteractions(aiClient, resultService);
    }
}

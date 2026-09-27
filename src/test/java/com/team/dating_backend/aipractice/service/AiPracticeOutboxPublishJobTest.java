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
    private static final Long TARGET_MEMBER_ID = 2L;
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 9, 27, 12, 0);

    private AiPracticeOutboxRepository outboxRepository;
    private AiPracticeSessionRepository sessionRepository;
    private AiPracticeChatRepository chatRepository;
    private AiPracticeAiClient aiClient;
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
        resultService = mock(AiPracticeOutboxResultService.class);
        properties = new AiPracticeProperties();
        job = new AiPracticeOutboxPublishJob(
            outboxRepository,
            sessionRepository,
            chatRepository,
            aiClient,
            resultService,
            properties);

        session = new AiPracticeSession(USER_ID, TARGET_MEMBER_ID, CREATED_AT);
        ReflectionTestUtils.setField(session, "id", SESSION_ID);
        chat = new AiPracticeChat(
            session, java.util.UUID.randomUUID(), "연습 메시지", AiPracticeTime.today(), CREATED_AT);
        ReflectionTestUtils.setField(chat, "id", CHAT_ID);
        outbox = new AiPracticeOutbox(
            AiPracticeOutboxCommandType.GENERATE, SESSION_ID, CHAT_ID, 1, CREATED_AT);
        ReflectionTestUtils.setField(outbox, "id", OUTBOX_ID);

        given(outboxRepository
            .findByPublishedAtIsNullAndFailedAtIsNullAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscIdAsc(
                any(LocalDateTime.class), any(Pageable.class)))
            .willReturn(List.of(outbox));
        given(sessionRepository.findById(SESSION_ID)).willReturn(Optional.of(session));
        given(chatRepository.findByIdAndSession_Id(CHAT_ID, SESSION_ID))
            .willReturn(Optional.of(chat));
    }

    @Test
    void 최초생성은AI세션을만든뒤메시지를보내고답변을저장한다() {
        given(aiClient.startSession(TARGET_MEMBER_ID)).willReturn("ai-session-42");
        given(aiClient.sendMessage(anyString(), anyString()))
            .willReturn(new GenerationReplyResponse("ai-session-42", 1, "AI 답변", "llm"));

        job.publishDueCommands();

        verify(aiClient).startSession(TARGET_MEMBER_ID);
        verify(aiClient).sendMessage("ai-session-42", "연습 메시지");
        verify(resultService).attachAiSessionId(OUTBOX_ID, "ai-session-42");
        verify(resultService).markGenerationCompleted(OUTBOX_ID, "ai-session-42", "AI 답변");
    }

    @Test
    void 기존AI세션이있으면세션ID와메시지로후속생성을요청한다() {
        session.attachAiSessionId("ai-session-42");
        given(aiClient.sendMessage(anyString(), anyString()))
            .willReturn(new GenerationReplyResponse("ai-session-42", 1, "AI 답변", "llm"));

        job.publishDueCommands();

        verify(aiClient).sendMessage("ai-session-42", "연습 메시지");
        verify(resultService).markGenerationCompleted(OUTBOX_ID, "ai-session-42", "AI 답변");
        verify(aiClient, never()).startSession(any());
    }

    @Test
    void 이미실패했거나오래된생성시도의Outbox는AI로전달하지않는다() {
        ReflectionTestUtils.setField(outbox, "generationAttempt", 0);

        job.publishDueCommands();

        verify(resultService).markSkipped(OUTBOX_ID);
        verifyNoInteractions(aiClient);
    }

    @Test
    void AI서버요청실패는정해진횟수와재시도시각을기록한다() {
        org.mockito.BDDMockito.willThrow(new RestClientException("request failed"))
            .given(aiClient).startSession(TARGET_MEMBER_ID);

        job.publishDueCommands();

        org.mockito.ArgumentCaptor<String> failureTypeCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<Integer> maxFailuresCaptor = org.mockito.ArgumentCaptor.forClass(Integer.class);
        org.mockito.ArgumentCaptor<LocalDateTime> retryAtCaptor = org.mockito.ArgumentCaptor
            .forClass(LocalDateTime.class);
        verify(resultService).recordFailure(
            org.mockito.ArgumentMatchers.eq(OUTBOX_ID),
            failureTypeCaptor.capture(),
            maxFailuresCaptor.capture(),
            retryAtCaptor.capture());
        assertThat(failureTypeCaptor.getValue()).isEqualTo("AI_SERVER_REQUEST_FAILED");
        assertThat(maxFailuresCaptor.getValue()).isEqualTo(10);
        assertThat(retryAtCaptor.getValue()).isAfter(AiPracticeTime.now());
        verify(resultService, never()).markGenerationCompleted(
            OUTBOX_ID, "ai-session-42", "AI 답변");
    }
}

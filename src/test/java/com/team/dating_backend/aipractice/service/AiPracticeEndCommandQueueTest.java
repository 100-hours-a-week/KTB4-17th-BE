package com.team.dating_backend.aipractice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.team.dating_backend.aipractice.entity.AiPracticeOutbox;
import com.team.dating_backend.aipractice.entity.AiPracticeSession;
import com.team.dating_backend.aipractice.enums.AiPracticeChatStatus;
import com.team.dating_backend.aipractice.enums.AiPracticeOutboxCommandType;
import com.team.dating_backend.aipractice.repository.AiPracticeChatRepository;
import com.team.dating_backend.aipractice.repository.AiPracticeOutboxRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class AiPracticeEndCommandQueueTest {

    private static final Long SESSION_ID = 10L;
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 9, 27, 12, 0);

    private AiPracticeChatRepository chatRepository;
    private AiPracticeOutboxRepository outboxRepository;
    private AiPracticeEndCommandQueue queue;
    private AiPracticeSession endedSession;

    @BeforeEach
    void setUp() {
        chatRepository = mock(AiPracticeChatRepository.class);
        outboxRepository = mock(AiPracticeOutboxRepository.class);
        queue = new AiPracticeEndCommandQueue(chatRepository, outboxRepository);
        endedSession = new AiPracticeSession(1L, 2L, CREATED_AT);
        ReflectionTestUtils.setField(endedSession, "id", SESSION_ID);
        endedSession.attachAiSessionId("ai-session-42");
        endedSession.end(CREATED_AT.plusMinutes(1));
        given(chatRepository.existsBySession_IdAndStatus(
            SESSION_ID, AiPracticeChatStatus.GENERATING)).willReturn(false);
    }

    @Test
    void 모든생성이끝난종료세션에AI종료명령을한번만추가한다() {
        queue.enqueueIfReady(endedSession);
        queue.enqueueIfReady(endedSession);

        ArgumentCaptor<AiPracticeOutbox> outboxCaptor = ArgumentCaptor.forClass(AiPracticeOutbox.class);
        verify(outboxRepository).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().getCommandType())
            .isEqualTo(AiPracticeOutboxCommandType.END_SESSION);
        assertThat(outboxCaptor.getValue().getSessionId()).isEqualTo(SESSION_ID);
        assertThat(outboxCaptor.getValue().getChatId()).isNull();
        assertThat(endedSession.isEndCommandEnqueued()).isTrue();
    }

    @Test
    void 생성중인턴이있으면AI종료명령을대기한다() {
        given(chatRepository.existsBySession_IdAndStatus(
            SESSION_ID, AiPracticeChatStatus.GENERATING)).willReturn(true);

        queue.enqueueIfReady(endedSession);

        verify(outboxRepository, never()).save(
            org.mockito.ArgumentMatchers.any(AiPracticeOutbox.class));
        assertThat(endedSession.isEndCommandEnqueued()).isFalse();
    }

    @Test
    void AI세션ID가없으면외부종료명령을생성하지않는다() {
        AiPracticeSession localOnlySession = new AiPracticeSession(1L, 2L, CREATED_AT);
        ReflectionTestUtils.setField(localOnlySession, "id", SESSION_ID);
        localOnlySession.end(CREATED_AT.plusMinutes(1));

        queue.enqueueIfReady(localOnlySession);

        verify(outboxRepository, never()).save(
            org.mockito.ArgumentMatchers.any(AiPracticeOutbox.class));
    }
}

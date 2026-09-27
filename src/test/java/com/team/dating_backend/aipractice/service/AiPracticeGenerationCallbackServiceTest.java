package com.team.dating_backend.aipractice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.team.dating_backend.aipractice.dto.event.AiPracticeTurnUpdatedEvent;
import com.team.dating_backend.aipractice.dto.request.AiPracticeGenerationCallbackRequest;
import com.team.dating_backend.aipractice.entity.AiPracticeChat;
import com.team.dating_backend.aipractice.entity.AiPracticeSession;
import com.team.dating_backend.aipractice.enums.AiPracticeCallbackStatus;
import com.team.dating_backend.aipractice.enums.AiPracticeChatStatus;
import com.team.dating_backend.aipractice.enums.AiPracticeErrorCode;
import com.team.dating_backend.aipractice.exception.AiPracticeBusinessException;
import com.team.dating_backend.aipractice.repository.AiPracticeChatRepository;
import com.team.dating_backend.aipractice.repository.AiPracticeSessionRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

class AiPracticeGenerationCallbackServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long SESSION_ID = 10L;
    private static final Long CHAT_ID = 20L;
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 9, 27, 12, 0);

    private AiPracticeSessionRepository sessionRepository;
    private AiPracticeChatRepository chatRepository;
    private AiPracticeEndCommandQueue endCommandQueue;
    private ApplicationEventPublisher eventPublisher;
    private AiPracticeGenerationCallbackService service;
    private AiPracticeSession session;
    private AiPracticeChat chat;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(AiPracticeSessionRepository.class);
        chatRepository = mock(AiPracticeChatRepository.class);
        endCommandQueue = mock(AiPracticeEndCommandQueue.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new AiPracticeGenerationCallbackService(
            sessionRepository, chatRepository, endCommandQueue, eventPublisher);

        session = new AiPracticeSession(USER_ID, 2L, CREATED_AT);
        ReflectionTestUtils.setField(session, "id", SESSION_ID);
        chat = new AiPracticeChat(
            session, UUID.randomUUID(), "연습 메시지", AiPracticeTime.today(), CREATED_AT);
        ReflectionTestUtils.setField(chat, "id", CHAT_ID);

        given(sessionRepository.findByIdForUpdate(SESSION_ID)).willReturn(Optional.of(session));
        given(chatRepository.findByIdAndSession_Id(CHAT_ID, SESSION_ID))
            .willReturn(Optional.of(chat));
    }

    @Test
    void 완료이벤트는AI응답을저장하고사용자알림이벤트를발행한다() {
        service.receive(callback(1, AiPracticeCallbackStatus.COMPLETED, "AI 응답"));

        assertThat(session.getAiSessionId()).isEqualTo("ai-session-42");
        assertThat(chat.getStatus()).isEqualTo(AiPracticeChatStatus.COMPLETED);
        assertThat(chat.getAiResponse()).isEqualTo("AI 응답");
        assertThat(chat.getCompletedAt()).isNotNull();

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue()).isInstanceOf(AiPracticeTurnUpdatedEvent.class);
        AiPracticeTurnUpdatedEvent event = (AiPracticeTurnUpdatedEvent) eventCaptor.getValue();
        assertThat(event.userId()).isEqualTo(USER_ID);
        assertThat(event.sessionId()).isEqualTo(SESSION_ID);
        assertThat(event.chatId()).isEqualTo(CHAT_ID);
        assertThat(event.status()).isEqualTo(AiPracticeChatStatus.COMPLETED);
        assertThat(event.aiResponse()).isEqualTo("AI 응답");
        verify(endCommandQueue).enqueueIfReady(session);
    }

    @Test
    void 빈응답의완료이벤트는생성실패로저장한다() {
        service.receive(callback(1, AiPracticeCallbackStatus.COMPLETED, " "));

        assertThat(chat.getStatus()).isEqualTo(AiPracticeChatStatus.FAILED);
        assertThat(chat.getFailureCode()).isEqualTo("AI_GENERATION_FAILED");
        assertThat(chat.getAiResponse()).isNull();
        verify(eventPublisher).publishEvent(org.mockito.ArgumentMatchers.any(
            AiPracticeTurnUpdatedEvent.class));
    }

    @Test
    void 오래된생성시도의콜백은현재턴상태를변경하지않는다() {
        chat.retry(AiPracticeTime.today());

        service.receive(callback(1, AiPracticeCallbackStatus.COMPLETED, "늦은 응답"));

        assertThat(chat.getStatus()).isEqualTo(AiPracticeChatStatus.GENERATING);
        assertThat(chat.getGenerationAttempt()).isEqualTo(2);
        verifyNoInteractions(eventPublisher);
        verify(endCommandQueue).enqueueIfReady(session);
    }

    @Test
    void 완료콜백중복수신은두번째알림을발행하지않는다() {
        chat.complete("이미 저장된 응답", CREATED_AT.plusMinutes(1));

        service.receive(callback(1, AiPracticeCallbackStatus.COMPLETED, "중복 응답"));

        assertThat(chat.getAiResponse()).isEqualTo("이미 저장된 응답");
        verifyNoInteractions(eventPublisher);
        verify(endCommandQueue, never()).enqueueIfReady(session);
    }

    @Test
    void 존재하지않는세션의콜백은무효콜백오류를반환한다() {
        given(sessionRepository.findByIdForUpdate(SESSION_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.receive(
            callback(1, AiPracticeCallbackStatus.COMPLETED, "AI 응답")))
            .isInstanceOf(AiPracticeBusinessException.class)
            .satisfies(exception -> assertThat(
                ((AiPracticeBusinessException) exception).getErrorCode())
                .isEqualTo(AiPracticeErrorCode.AI_CALLBACK_INVALID));
        verifyNoInteractions(eventPublisher);
    }

    private AiPracticeGenerationCallbackRequest callback(
        int attempt, AiPracticeCallbackStatus status, String aiResponse) {
        return new AiPracticeGenerationCallbackRequest(
            SESSION_ID, CHAT_ID, "ai-session-42", attempt, status, aiResponse);
    }
}

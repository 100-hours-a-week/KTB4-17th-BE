package com.team.dating_backend.aipractice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.team.dating_backend.aipractice.config.AiPracticeProperties;
import com.team.dating_backend.aipractice.dto.request.AiPracticeChatCreateRequest;
import com.team.dating_backend.aipractice.dto.response.AiPracticeChatAcceptedResponse;
import com.team.dating_backend.aipractice.dto.response.AiPracticeUsageResponse;
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
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.repository.UserRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class AiPracticeChatServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long SESSION_ID = 10L;
    private static final Long CHAT_ID = 20L;
    private static final Long TARGET_MEMBER_ID = 2L;
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 9, 27, 12, 0);

    private AiPracticeChatRepository chatRepository;
    private AiPracticeOutboxRepository outboxRepository;
    private AiPracticeSessionRepository sessionRepository;
    private UserRepository userRepository;
    private AiPracticeChatService service;
    private AiPracticeSession session;
    private AiPracticeChatCreateRequest request;

    @BeforeEach
    void setUp() {
        chatRepository = mock(AiPracticeChatRepository.class);
        outboxRepository = mock(AiPracticeOutboxRepository.class);
        sessionRepository = mock(AiPracticeSessionRepository.class);
        userRepository = mock(UserRepository.class);
        service = new AiPracticeChatService(
            sessionRepository,
            chatRepository,
            outboxRepository,
            userRepository,
            new AiPracticeProperties());

        session = new AiPracticeSession(USER_ID, TARGET_MEMBER_ID, CREATED_AT);
        ReflectionTestUtils.setField(session, "id", SESSION_ID);
        request = new AiPracticeChatCreateRequest(UUID.randomUUID(), "첫 메시지");

        given(userRepository.findByIdForUpdate(USER_ID)).willReturn(Optional.of(mock(User.class)));
        given(sessionRepository.findByIdAndUserIdForUpdate(SESSION_ID, USER_ID))
            .willReturn(Optional.of(session));
        given(chatRepository.findBySession_IdAndClientMessageId(
            SESSION_ID, request.clientMessageId())).willReturn(Optional.empty());
        given(chatRepository.existsBySession_IdAndStatus(
            SESSION_ID, AiPracticeChatStatus.GENERATING)).willReturn(false);
        given(chatRepository.countReservedAndCompletedUsage(
            eq(USER_ID), any(LocalDate.class), anySet())).willReturn(0L);
        given(chatRepository.save(any(AiPracticeChat.class))).willAnswer(invocation -> {
            AiPracticeChat chat = invocation.getArgument(0);
            ReflectionTestUtils.setField(chat, "id", CHAT_ID);
            return chat;
        });
    }

    @Test
    void 메시지와생성Outbox를저장하고생성중응답을반환한다() {
        AiPracticeChatAcceptedResponse response = service.sendMessage(USER_ID, SESSION_ID, request);

        ArgumentCaptor<AiPracticeChat> chatCaptor = ArgumentCaptor.forClass(AiPracticeChat.class);
        ArgumentCaptor<AiPracticeOutbox> outboxCaptor = ArgumentCaptor.forClass(AiPracticeOutbox.class);
        verify(chatRepository).save(chatCaptor.capture());
        verify(outboxRepository).save(outboxCaptor.capture());

        AiPracticeChat chat = chatCaptor.getValue();
        AiPracticeOutbox outbox = outboxCaptor.getValue();
        assertThat(response.sessionId()).isEqualTo(SESSION_ID);
        assertThat(response.chatId()).isEqualTo(CHAT_ID);
        assertThat(response.status()).isEqualTo(AiPracticeChatStatus.GENERATING);
        assertThat(chat.getClientMessageId()).isEqualTo(request.clientMessageId());
        assertThat(chat.getSession()).isSameAs(session);
        assertThat(chat.getUserMessage()).isEqualTo(request.userMessage());
        assertThat(chat.getUsageDate()).isEqualTo(AiPracticeTime.today());
        assertThat(outbox.getCommandType()).isEqualTo(AiPracticeOutboxCommandType.GENERATE);
        assertThat(outbox.getSessionId()).isEqualTo(SESSION_ID);
        assertThat(outbox.getChatId()).isEqualTo(CHAT_ID);
        assertThat(outbox.getGenerationAttempt()).isEqualTo(1);
    }

    @Test
    void 동일한클라이언트메시지재요청은기존턴을반환한다() {
        AiPracticeChat existing = new AiPracticeChat(
            session, request.clientMessageId(), request.userMessage(),
            AiPracticeTime.today(), CREATED_AT);
        ReflectionTestUtils.setField(existing, "id", CHAT_ID);
        given(chatRepository.findBySession_IdAndClientMessageId(
            SESSION_ID, request.clientMessageId())).willReturn(Optional.of(existing));

        AiPracticeChatAcceptedResponse response = service.sendMessage(USER_ID, SESSION_ID, request);

        assertThat(response.chatId()).isEqualTo(CHAT_ID);
        verify(chatRepository, never()).save(any(AiPracticeChat.class));
        verify(outboxRepository, never()).save(any(AiPracticeOutbox.class));
    }

    @Test
    void 동일한클라이언트메시지ID에다른내용이면충돌오류를반환한다() {
        AiPracticeChat existing = new AiPracticeChat(
            session, request.clientMessageId(), "다른 메시지",
            AiPracticeTime.today(), CREATED_AT);
        ReflectionTestUtils.setField(existing, "id", CHAT_ID);
        given(chatRepository.findBySession_IdAndClientMessageId(
            SESSION_ID, request.clientMessageId())).willReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.sendMessage(USER_ID, SESSION_ID, request))
            .isInstanceOf(AiPracticeBusinessException.class)
            .satisfies(exception -> assertThat(
                ((AiPracticeBusinessException) exception).getErrorCode())
                .isEqualTo(AiPracticeErrorCode.CLIENT_MESSAGE_ID_CONFLICT));

        verify(chatRepository, never()).save(any(AiPracticeChat.class));
        verify(outboxRepository, never()).save(any(AiPracticeOutbox.class));
    }

    @Test
    void 일일한도를모두사용했으면메시지와Outbox를저장하지않는다() {
        given(chatRepository.countReservedAndCompletedUsage(
            eq(USER_ID), any(LocalDate.class), anySet())).willReturn(30L);

        assertThatThrownBy(() -> service.sendMessage(USER_ID, SESSION_ID, request))
            .isInstanceOf(AiPracticeBusinessException.class)
            .satisfies(exception -> assertThat(
                ((AiPracticeBusinessException) exception).getErrorCode())
                .isEqualTo(AiPracticeErrorCode.DAILY_LIMIT_EXCEEDED));

        verify(chatRepository, never()).save(any(AiPracticeChat.class));
        verify(outboxRepository, never()).save(any(AiPracticeOutbox.class));
    }

    @Test
    void 사용량조회는완료횟수와생성예약횟수를분리해반환한다() {
        LocalDate today = AiPracticeTime.today();
        given(chatRepository.countUsageByUserIdAndUsageDateAndStatus(
            USER_ID, today, AiPracticeChatStatus.COMPLETED)).willReturn(12L);
        given(chatRepository.countUsageByUserIdAndUsageDateAndStatus(
            USER_ID, today, AiPracticeChatStatus.GENERATING)).willReturn(3L);

        AiPracticeUsageResponse response = service.getTodayUsage(USER_ID);

        assertThat(response.usageDate()).isEqualTo(today);
        assertThat(response.used()).isEqualTo(12L);
        assertThat(response.reserved()).isEqualTo(3L);
        assertThat(response.dailyLimit()).isEqualTo(30);
        assertThat(response.remaining()).isEqualTo(15);
    }

    @Test
    void 실패턴재시도는시도횟수를올리고새Outbox를저장한다() {
        AiPracticeChat failedChat = new AiPracticeChat(
            session, request.clientMessageId(), request.userMessage(),
            AiPracticeTime.today(), CREATED_AT);
        failedChat.fail("AI_GENERATION_FAILED");
        ReflectionTestUtils.setField(failedChat, "id", CHAT_ID);
        given(chatRepository.findByIdAndSession_Id(CHAT_ID, SESSION_ID))
            .willReturn(Optional.of(failedChat));

        AiPracticeChatAcceptedResponse response = service.retry(USER_ID, SESSION_ID, CHAT_ID);

        assertThat(response.status()).isEqualTo(AiPracticeChatStatus.GENERATING);
        assertThat(failedChat.isRetry()).isTrue();
        assertThat(failedChat.getGenerationAttempt()).isEqualTo(2);
        ArgumentCaptor<AiPracticeOutbox> outboxCaptor = ArgumentCaptor.forClass(AiPracticeOutbox.class);
        verify(outboxRepository).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().getGenerationAttempt()).isEqualTo(2);
    }
}

package com.team.dating_backend.aipractice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.team.dating_backend.aipractice.entity.AiPracticeChat;
import com.team.dating_backend.aipractice.entity.AiPracticeSession;
import com.team.dating_backend.aipractice.enums.AiPracticeChatStatus;
import com.team.dating_backend.aipractice.enums.AiPracticeFailureCode;
import com.team.dating_backend.aipractice.enums.AiPracticeSessionStatus;
import com.team.dating_backend.aipractice.repository.AiPracticeChatRepository;
import com.team.dating_backend.aipractice.repository.AiPracticeSessionRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class AiPracticeWithdrawalTerminationServiceTest {

    private static final Long USER_ID = 1L;
    private static final LocalDateTime ENDED_AT = LocalDateTime.of(2026, 10, 15, 12, 0);

    private AiPracticeSessionRepository sessionRepository;
    private AiPracticeChatRepository chatRepository;
    private AiPracticeEndCommandQueue endCommandQueue;
    private AiPracticeWithdrawalTerminationService service;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(AiPracticeSessionRepository.class);
        chatRepository = mock(AiPracticeChatRepository.class);
        endCommandQueue = mock(AiPracticeEndCommandQueue.class);
        service = new AiPracticeWithdrawalTerminationService(
            sessionRepository, chatRepository, endCommandQueue);
    }

    @Test
    void 실행자이거나_상대방인_활성_세션과_생성중_대화를_종료한다() {
        AiPracticeSession ownedSession = session(10L, USER_ID, 2L);
        AiPracticeSession targetedSession = session(11L, 2L, USER_ID);
        AiPracticeChat ownedChat = chat(ownedSession);
        AiPracticeChat targetedChat = chat(targetedSession);
        given(sessionRepository.findAllForUserByStatusForUpdate(
            USER_ID, AiPracticeSessionStatus.ACTIVE))
            .willReturn(List.of(ownedSession, targetedSession));
        given(chatRepository.findAllBySession_IdAndStatus(
            10L, AiPracticeChatStatus.GENERATING)).willReturn(List.of(ownedChat));
        given(chatRepository.findAllBySession_IdAndStatus(
            11L, AiPracticeChatStatus.GENERATING)).willReturn(List.of(targetedChat));

        service.terminateForUser(USER_ID, ENDED_AT);

        assertThat(List.of(ownedSession, targetedSession)).allSatisfy(session -> {
            assertThat(session.getStatus()).isEqualTo(AiPracticeSessionStatus.ENDED);
            assertThat(session.getEndedAt()).isEqualTo(ENDED_AT);
        });
        assertThat(List.of(ownedChat, targetedChat)).allSatisfy(chat -> {
            assertThat(chat.getStatus()).isEqualTo(AiPracticeChatStatus.FAILED);
            assertThat(chat.getFailureCode()).isEqualTo(AiPracticeFailureCode.USER_WITHDRAWN.name());
        });
        verify(endCommandQueue).enqueueIfReady(ownedSession);
        verify(endCommandQueue).enqueueIfReady(targetedSession);
    }

    private AiPracticeSession session(Long id, Long userId, Long targetUserId) {
        AiPracticeSession session = new AiPracticeSession(userId, targetUserId, ENDED_AT.minusHours(1));
        ReflectionTestUtils.setField(session, "id", id);
        return session;
    }

    private AiPracticeChat chat(AiPracticeSession session) {
        return new AiPracticeChat(
            session, UUID.randomUUID(), "연습 메시지", AiPracticeTime.today(), ENDED_AT.minusMinutes(1));
    }
}

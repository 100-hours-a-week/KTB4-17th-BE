package com.team.dating_backend.aipractice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.team.dating_backend.aipractice.dto.response.AiPracticeChatPageResponse;
import com.team.dating_backend.aipractice.dto.response.AiPracticeSessionResponse;
import com.team.dating_backend.aipractice.entity.AiPracticeChat;
import com.team.dating_backend.aipractice.entity.AiPracticeSession;
import com.team.dating_backend.aipractice.enums.AiPracticeSessionStatus;
import com.team.dating_backend.aipractice.repository.AiPracticeChatRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

class AiPracticeHistoryServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long SESSION_ID = 10L;
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 9, 27, 12, 0);

    private AiPracticeSessionService sessionService;
    private AiPracticeChatRepository chatRepository;
    private AiPracticeHistoryService service;
    private AiPracticeSession session;
    private AiPracticeSessionResponse sessionResponse;
    private AiPracticeChat firstChat;
    private AiPracticeChat secondChat;

    @BeforeEach
    void setUp() {
        sessionService = mock(AiPracticeSessionService.class);
        chatRepository = mock(AiPracticeChatRepository.class);
        service = new AiPracticeHistoryService(sessionService, chatRepository);

        session = new AiPracticeSession(USER_ID, 2L, CREATED_AT);
        ReflectionTestUtils.setField(session, "id", SESSION_ID);
        sessionResponse = new AiPracticeSessionResponse(
            SESSION_ID, 2L, AiPracticeSessionStatus.ACTIVE, CREATED_AT, null, false);
        given(sessionService.requireOwnedSession(SESSION_ID, USER_ID)).willReturn(session);
        given(sessionService.toResponse(session, false)).willReturn(sessionResponse);

        firstChat = chat(11L, "첫 메시지");
        secondChat = chat(22L, "두 번째 메시지");
    }

    @Test
    void 대화이력은ID오름차순으로조회하고ID를커서로사용한다() {
        given(chatRepository.findBySession_IdOrderByIdAsc(SESSION_ID, PageRequest.of(0, 2)))
            .willReturn(List.of(firstChat, secondChat));
        given(chatRepository.findBySession_IdAndIdGreaterThanOrderByIdAsc(
            SESSION_ID, 11L, PageRequest.of(0, 2)))
            .willReturn(List.of(secondChat));

        AiPracticeChatPageResponse firstPage = service.getHistory(USER_ID, SESSION_ID, null, 1);
        AiPracticeChatPageResponse nextPage = service.getHistory(USER_ID, SESSION_ID, 11L, 1);

        assertThat(firstPage.chats()).extracting("id").containsExactly(11L);
        assertThat(firstPage.hasNext()).isTrue();
        assertThat(firstPage.nextCursor()).isEqualTo(11L);
        assertThat(nextPage.chats()).extracting("id").containsExactly(22L);
        assertThat(nextPage.hasNext()).isFalse();
        assertThat(nextPage.nextCursor()).isNull();
        verify(chatRepository).findBySession_IdOrderByIdAsc(SESSION_ID, PageRequest.of(0, 2));
        verify(chatRepository).findBySession_IdAndIdGreaterThanOrderByIdAsc(
            SESSION_ID, 11L, PageRequest.of(0, 2));
    }

    private AiPracticeChat chat(Long id, String message) {
        AiPracticeChat chat = new AiPracticeChat(
            session, UUID.randomUUID(), message, AiPracticeTime.today(), CREATED_AT);
        ReflectionTestUtils.setField(chat, "id", id);
        return chat;
    }
}

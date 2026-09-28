package com.team.dating_backend.aipractice.service;

import com.team.dating_backend.aipractice.dto.response.AiPracticeChatPageResponse;
import com.team.dating_backend.aipractice.dto.response.AiPracticeChatResponse;
import com.team.dating_backend.aipractice.dto.response.AiPracticeSessionResponse;
import com.team.dating_backend.aipractice.entity.AiPracticeChat;
import com.team.dating_backend.aipractice.entity.AiPracticeSession;
import com.team.dating_backend.aipractice.repository.AiPracticeChatRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AiPracticeHistoryService {

    private final AiPracticeSessionService sessionService;
    private final AiPracticeChatRepository chatRepository;

    @Transactional(readOnly = true)
    public AiPracticeChatPageResponse getHistory(
        Long userId, Long sessionId, Long cursor, int size) {
        AiPracticeSession session = sessionService.requireOwnedSession(sessionId, userId);
        List<AiPracticeChat> fetched = cursor == null
            ? chatRepository.findBySession_IdOrderByIdAsc(
                sessionId, PageRequest.of(0, size + 1))
            : chatRepository.findBySession_IdAndIdGreaterThanOrderByIdAsc(
                sessionId, cursor, PageRequest.of(0, size + 1));

        boolean hasNext = fetched.size() > size;
        List<AiPracticeChatResponse> chats = fetched.stream()
            .limit(size)
            .map(this::toResponse)
            .toList();
        Long nextCursor = hasNext && !chats.isEmpty()
            ? chats.getLast().id()
            : null;
        AiPracticeSessionResponse sessionResponse = sessionService.toResponse(session, false);
        return new AiPracticeChatPageResponse(sessionResponse, chats, hasNext, nextCursor);
    }

    private AiPracticeChatResponse toResponse(AiPracticeChat chat) {
        return new AiPracticeChatResponse(
            chat.getId(),
            chat.getClientMessageId(),
            chat.getUserMessage(),
            chat.getAiResponse(),
            chat.getStatus(),
            chat.isRetry(),
            chat.getFailureCode(),
            chat.getCreatedAt(),
            chat.getCompletedAt());
    }
}

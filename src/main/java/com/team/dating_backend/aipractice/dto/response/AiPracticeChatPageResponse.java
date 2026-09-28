package com.team.dating_backend.aipractice.dto.response;

import java.util.List;

public record AiPracticeChatPageResponse(
    AiPracticeSessionResponse session,
    List<AiPracticeChatResponse> chats,
    boolean hasNext,
    Long nextCursor) {}

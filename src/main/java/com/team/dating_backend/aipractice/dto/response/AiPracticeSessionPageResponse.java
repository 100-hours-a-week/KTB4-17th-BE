package com.team.dating_backend.aipractice.dto.response;

import java.util.List;

public record AiPracticeSessionPageResponse(
    List<AiPracticeSessionResponse> sessions,
    boolean hasNext,
    Long nextCursor) {}

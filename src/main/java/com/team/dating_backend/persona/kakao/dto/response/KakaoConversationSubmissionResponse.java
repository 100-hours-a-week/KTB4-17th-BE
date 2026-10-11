package com.team.dating_backend.persona.kakao.dto.response;

import java.time.OffsetDateTime;

public record KakaoConversationSubmissionResponse(
    String submissionId,
    String status,
    String personaId,
    int analyzedCount,
    String errorCode,
    OffsetDateTime createdAt,
    OffsetDateTime finishedAt) {}

package com.team.dating_backend.persona.kakao.dto.response;

import java.time.OffsetDateTime;

public record KakaoExtractionResponse(
    String jobId,
    String status,
    String jobToken,
    OffsetDateTime jobTokenExpiresAt) {}

package com.team.dating_backend.persona.dto.response;

import java.time.OffsetDateTime;

public record PersonaConfirmationResponse(
    String personaId,
    boolean confirmed,
    String mbti,
    OffsetDateTime confirmedAt) {}

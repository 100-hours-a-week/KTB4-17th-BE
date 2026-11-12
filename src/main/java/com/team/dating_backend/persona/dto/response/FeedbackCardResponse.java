package com.team.dating_backend.persona.dto.response;

import java.time.OffsetDateTime;

public record FeedbackCardResponse(
    String cardId,
    String contextQuestion,
    String utterance,
    String question,
    OffsetDateTime expiresAt) {}

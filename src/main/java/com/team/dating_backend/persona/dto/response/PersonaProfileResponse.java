package com.team.dating_backend.persona.dto.response;

import java.time.OffsetDateTime;
import java.util.List;

public record PersonaProfileResponse(
    String userId,
    String personaId,
    Integer version,
    String nickname,
    String mbti,
    OffsetDateTime confirmedAt,
    PersonaNarrativeResponse narrative,
    PersonaConversationStyleResponse conversationStyle,
    List<PersonaSummaryResponse> summaries,
    List<String> interests,
    List<String> routine,
    List<String> datePrefer,
    List<String> dateAvoid) {

    public PersonaProfileResponse {
        summaries = List.copyOf(summaries);
        interests = List.copyOf(interests);
        routine = List.copyOf(routine);
        datePrefer = List.copyOf(datePrefer);
        dateAvoid = List.copyOf(dateAvoid);
    }
}

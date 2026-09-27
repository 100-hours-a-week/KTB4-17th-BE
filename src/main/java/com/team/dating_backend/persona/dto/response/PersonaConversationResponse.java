package com.team.dating_backend.persona.dto.response;

import java.util.List;

public record PersonaConversationResponse(
    String sessionId,
    String utterance,
    List<PersonaSegmentResponse> segments,
    String progress,
    boolean done,
    int answered,
    boolean canSkip,
    boolean canFinish,
    boolean retry,
    int turnIndex,
    PersonaDraftResponse personaDraft) {

    public PersonaConversationResponse {
        segments = List.copyOf(segments);
    }
}

package com.team.dating_backend.persona.dto.response;

import java.util.List;

public record PersonaConversationStyleResponse(
    String summary,
    List<String> frequentPhrases,
    List<String> endings,
    List<String> slang,
    String messageLength,
    String laughter,
    String questionRate) {

    public PersonaConversationStyleResponse {
        frequentPhrases = List.copyOf(frequentPhrases);
        endings = List.copyOf(endings);
        slang = List.copyOf(slang);
    }
}

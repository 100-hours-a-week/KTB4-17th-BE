package com.team.dating_backend.persona.dto.response;

import java.util.List;

public record PersonaDraftResponse(
    String personaId,
    int version,
    String source,
    PersonaNarrativeResponse narrative,
    List<PersonaSummaryResponse> summaries) {

    public PersonaDraftResponse {
        summaries = List.copyOf(summaries);
    }
}

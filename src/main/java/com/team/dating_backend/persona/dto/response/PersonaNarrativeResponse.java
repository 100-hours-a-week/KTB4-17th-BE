package com.team.dating_backend.persona.dto.response;

import java.util.List;

public record PersonaNarrativeResponse(
    String headline,
    String body,
    List<String> traits) {

    public PersonaNarrativeResponse {
        traits = List.copyOf(traits);
    }
}

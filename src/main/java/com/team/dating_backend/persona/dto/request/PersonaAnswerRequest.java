package com.team.dating_backend.persona.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PersonaAnswerRequest(
    @NotBlank @Size(max = 200) String answer,
    @NotNull @Min(0) Integer turnIndex) {

    public PersonaAnswerRequest {
        if (answer != null) {
            answer = answer.trim();
        }
    }
}

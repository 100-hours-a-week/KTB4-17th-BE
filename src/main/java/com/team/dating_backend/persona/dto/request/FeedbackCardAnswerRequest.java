package com.team.dating_backend.persona.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

public record FeedbackCardAnswerRequest(
    @NotNull Answer answer,
    @Size(max = 200) String correctionText,
    @NotNull OffsetDateTime answeredAt,
    @NotBlank @Size(max = 64) String consentVersion) {

    @JsonIgnore
    @AssertTrue(message = "correctionText is only allowed when answer is NO")
    public boolean isCorrectionConsistent() {
        return answer != Answer.YES || correctionText == null;
    }

    public enum Answer {
        YES,
        NO
    }
}

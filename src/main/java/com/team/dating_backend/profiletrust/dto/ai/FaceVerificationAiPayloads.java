package com.team.dating_backend.profiletrust.dto.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.team.dating_backend.profiletrust.enums.VerificationDecision;
import com.team.dating_backend.profiletrust.enums.VerificationReasonCode;
import java.time.OffsetDateTime;
import java.util.List;

public final class FaceVerificationAiPayloads {

    private FaceVerificationAiPayloads() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChallengeResponse(
        @JsonProperty("challenge_token") String challengeToken,
        List<String> challenges,
        @JsonProperty("expires_at") OffsetDateTime expiresAt) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Liveness(Boolean passed) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record VerificationResponse(
        VerificationDecision decision,
        @JsonProperty("reason_codes") List<VerificationReasonCode> reasonCodes,
        Liveness liveness) {}
}

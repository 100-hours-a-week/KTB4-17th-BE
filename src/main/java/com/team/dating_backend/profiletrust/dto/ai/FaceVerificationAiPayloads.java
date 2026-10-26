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
        @JsonProperty("expires_at") OffsetDateTime expiresAt,
        @JsonProperty("challenge_plan") List<List<String>> challengePlan,
        @JsonProperty("protocol_version") Integer protocolVersion) {

        public ChallengeResponse {
            protocolVersion = protocolVersion == null ? 1 : protocolVersion;
        }

        public ChallengeResponse(String challengeToken, List<String> challenges, OffsetDateTime expiresAt) {
            this(challengeToken, challenges, expiresAt, null, 1);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Liveness(Boolean passed) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record VerificationResponse(
        VerificationDecision decision,
        @JsonProperty("reason_codes") List<VerificationReasonCode> reasonCodes,
        Liveness liveness) {}
}

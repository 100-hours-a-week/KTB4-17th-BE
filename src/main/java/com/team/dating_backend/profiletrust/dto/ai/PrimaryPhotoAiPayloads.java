package com.team.dating_backend.profiletrust.dto.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public final class PrimaryPhotoAiPayloads {

    private PrimaryPhotoAiPayloads() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FrontalCheckResponse(
        String decision,
        @JsonProperty("reason_codes") List<String> reasonCodes) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SyntheticCheckResponse(String decision) {}
}
